package com.github.h3yow.tradingenginejava.matching.reconcile;

import com.github.h3yow.tradingenginejava.matching.engine.MatchingEngine;
import com.github.h3yow.tradingenginejava.matching.model.Order;
import com.github.h3yow.tradingenginejava.matching.model.OrderStatus;
import com.github.h3yow.tradingenginejava.matching.model.Trade;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 盘后对账：按账户、按标的生成确定性摘要，并能把差异定位到
 * “哪个账户/标的、哪个维度”。
 *
 * <p><b>口径：</b>
 * <ul>
 *   <li>订单数 orderCount：该账户“成功落地”的委托数（即未被 REJECTED 的委托，
 *       含已成交/部分成交/撤销/在簿）；被拒委托从未进入订单簿，不计入；</li>
 *   <li>成交笔数 tradeCount：该账户作为买方或卖方参与的成交笔数；</li>
 *   <li>成交量 filledQuantity：该账户参与成交的数量之和（买卖两边各计一次，
 *       与标的维度双边统计一致）；</li>
 *   <li>现金 cash：账户现金总量（{@code cash}，含冻结）；</li>
 *   <li>持仓 position：该账户在各标的上的净持仓，由成交流水逐笔累加得出
 *       （买方 +qty、卖方 −qty），与账户总持仓互为印证；</li>
 *   <li>标的维度按成交流水统计双边成交笔数与成交量。</li>
 * </ul>
 * 摘要只依赖引擎内的有序结构（TreeMap/成交序），同一状态重复生成结果逐字段一致；
 * 序列化为 JSON 即逐字节一致（配合 {@code JsonIO}）。
 */
public final class Reconciler {

    private Reconciler() {
    }

    /** 从引擎当前状态生成确定性对账摘要。 */
    public static ReconciliationSummary summarize(MatchingEngine engine) {
        // 账户维度初始化（确保没有任何成交的账户也出现在摘要里）
        TreeMap<String, long[]> accTradeAgg = new TreeMap<>();
        TreeMap<String, TreeMap<String, Long>> accPositions = new TreeMap<>();
        for (String accountId : engine.accountIds()) {
            accTradeAgg.put(accountId, new long[2]); // [tradeCount, filledQuantity]
            accPositions.put(accountId, new TreeMap<>());
        }

        // 标的维度
        TreeMap<String, long[]> symAgg = new TreeMap<>();

        // 成交流水是唯一事实源：逐笔累加到买方/卖方账户与标的
        for (Trade t : engine.getTrades()) {
            bump(accTradeAgg, t.buyerAccountId(), t.quantity());
            bump(accTradeAgg, t.sellerAccountId(), t.quantity());
            accPositions.get(t.buyerAccountId()).merge(t.symbol(), t.quantity(), Long::sum);
            accPositions.get(t.sellerAccountId()).merge(t.symbol(), -t.quantity(), Long::sum);

            long[] sa = symAgg.computeIfAbsent(t.symbol(), k -> new long[2]);
            sa[0] += 1;
            sa[1] += t.quantity();
        }

        // 订单数：按账户统计成功落地的委托
        TreeMap<String, long[]> accOrderAgg = new TreeMap<>();
        for (Order o : engine.getAllOrders()) {
            if (o.getStatus() == OrderStatus.REJECTED) {
                continue;
            }
            accOrderAgg.computeIfAbsent(o.getAccountId(), k -> new long[1])[0] += 1;
        }

        TreeMap<String, ReconciliationSummary.AccountSummary> accounts = new TreeMap<>();
        for (String accountId : accTradeAgg.keySet()) {
            long[] tr = accTradeAgg.get(accountId);
            long orderCount = accOrderAgg.getOrDefault(accountId, new long[1])[0];
            String cash = engine.getAccount(accountId).getCash().toPlainString();
            // 净持仓为 0 的标的也保留（成交过即有审计轨迹）
            TreeMap<String, Long> positions = accPositions.get(accountId);
            accounts.put(accountId, new ReconciliationSummary.AccountSummary(
                    orderCount, tr[0], tr[1], cash, positions));
        }

        TreeMap<String, ReconciliationSummary.SymbolSummary> symbols = new TreeMap<>();
        for (Map.Entry<String, long[]> e : symAgg.entrySet()) {
            symbols.put(e.getKey(),
                    new ReconciliationSummary.SymbolSummary(e.getValue()[0], e.getValue()[1]));
        }
        return new ReconciliationSummary(accounts, symbols);
    }

    private static void bump(TreeMap<String, long[]> agg, String accountId, long qty) {
        long[] v = agg.get(accountId);
        if (v == null) {
            // 防御：成交对手账户理应已注册
            v = new long[2];
            agg.put(accountId, v);
        }
        v[0] += 1;
        v[1] += qty;
    }

    /** 逐账户、逐标的、逐维度比对两份摘要；完全一致返回空列表，否则返回全部差异项。 */
    public static List<Mismatch> diff(ReconciliationSummary expected,
                                      ReconciliationSummary actual) {
        List<Mismatch> mismatches = new ArrayList<>();

        // 账户集合（缺账户本身就是现金等维度对不上，按 ACCOUNT/cash 维度报出）
        for (String id : expected.accounts().keySet()) {
            var a = expected.accounts().get(id);
            var b = actual.accounts().get(id);
            if (b == null) {
                mismatches.add(new Mismatch(Mismatch.Scope.ACCOUNT, id, "presence",
                        "present", "absent"));
                continue;
            }
            if (a.orderCount() != b.orderCount()) {
                mismatches.add(new Mismatch(Mismatch.Scope.ACCOUNT, id, "orderCount",
                        Long.toString(a.orderCount()), Long.toString(b.orderCount())));
            }
            if (a.tradeCount() != b.tradeCount()) {
                mismatches.add(new Mismatch(Mismatch.Scope.ACCOUNT, id, "tradeCount",
                        Long.toString(a.tradeCount()), Long.toString(b.tradeCount())));
            }
            if (a.filledQuantity() != b.filledQuantity()) {
                mismatches.add(new Mismatch(Mismatch.Scope.ACCOUNT, id, "filledQuantity",
                        Long.toString(a.filledQuantity()), Long.toString(b.filledQuantity())));
            }
            if (!a.cash().equals(b.cash())) {
                mismatches.add(new Mismatch(Mismatch.Scope.ACCOUNT, id, "cash",
                        a.cash(), b.cash()));
            }
            for (Map.Entry<String, Long> pe : a.positions().entrySet()) {
                Long bv = b.positions().get(pe.getKey());
                String bText = bv == null ? "absent" : Long.toString(bv);
                if (bv == null || bv.longValue() != pe.getValue()) {
                    mismatches.add(new Mismatch(Mismatch.Scope.ACCOUNT,
                            id + "/" + pe.getKey(), "position",
                            Long.toString(pe.getValue()), bText));
                }
            }
            for (String symbol : b.positions().keySet()) {
                if (!a.positions().containsKey(symbol)) {
                    mismatches.add(new Mismatch(Mismatch.Scope.ACCOUNT,
                            id + "/" + symbol, "position",
                            "absent", Long.toString(b.positions().get(symbol))));
                }
            }
        }
        for (String id : actual.accounts().keySet()) {
            if (!expected.accounts().containsKey(id)) {
                mismatches.add(new Mismatch(Mismatch.Scope.ACCOUNT, id, "presence",
                        "absent", "present"));
            }
        }

        // 标的集合
        for (String symbol : expected.symbols().keySet()) {
            var a = expected.symbols().get(symbol);
            var b = actual.symbols().get(symbol);
            if (b == null) {
                mismatches.add(new Mismatch(Mismatch.Scope.SYMBOL, symbol, "presence",
                        "present", "absent"));
                continue;
            }
            if (a.tradeCount() != b.tradeCount()) {
                mismatches.add(new Mismatch(Mismatch.Scope.SYMBOL, symbol, "tradeCount",
                        Long.toString(a.tradeCount()), Long.toString(b.tradeCount())));
            }
            if (a.filledQuantity() != b.filledQuantity()) {
                mismatches.add(new Mismatch(Mismatch.Scope.SYMBOL, symbol, "filledQuantity",
                        Long.toString(a.filledQuantity()), Long.toString(b.filledQuantity())));
            }
        }
        for (String symbol : actual.symbols().keySet()) {
            if (!expected.symbols().containsKey(symbol)) {
                mismatches.add(new Mismatch(Mismatch.Scope.SYMBOL, symbol, "presence",
                        "absent", "present"));
            }
        }
        return mismatches;
    }

    /** 便捷方法：两台引擎状态一致当且仅当差异列表为空。 */
    public static List<Mismatch> compare(MatchingEngine expected, MatchingEngine actual) {
        return diff(summarize(expected), summarize(actual));
    }
}
