package com.github.h3yow.tradingenginejava.matching.digest;

import com.github.h3yow.tradingenginejava.matching.model.Account;
import com.github.h3yow.tradingenginejava.matching.model.Order;
import com.github.h3yow.tradingenginejava.matching.model.Trade;
import com.github.h3yow.tradingenginejava.matching.util.JsonIO;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 盘后对账摘要（确定性、可复现、可定位差异）。
 *
 * <p>口径：
 * <ul>
 *   <li>按账户：现金四分量（cash / reservedCash / position / reservedPosition）；</li>
 *   <li>按账户 × 标的：订单数（含已终结）、成交笔数（该账户作为买或卖参与的笔数）、
 *       成交量（买入量 / 卖出量分列）。</li>
 * </ul>
 *
 * <p>确定性：全部容器为 TreeMap（键字典序），数值用 plain string 表示；
 * 同一状态导出两次的 JSON 逐字节相同，{@link #hash()} 亦相同。
 * 两台实例摘要一致（hash 相等）即状态一致；不一致时 {@link #diff} 定位到
 * 具体账户 / 标的 / 维度。
 */
public final class ReconciliationDigest {

    /** 摘要对应的撮合水位（指令切点）。 */
    public long lastSeq;
    /** 账户名 -> 现金/持仓四分量。 */
    public Map<String, AccountLine> accounts = new TreeMap<>();
    /** 账户名 -> 标的 -> 该账户在该标的上的订单/成交口径。 */
    public Map<String, Map<String, SymbolLine>> accountSymbols = new TreeMap<>();

    /** 账户维度行：现金与持仓四分量（字符串表示，避免序列化精度漂移）。 */
    public record AccountLine(String cash, String reservedCash,
                              long position, long reservedPosition) {
    }

    /** 账户 × 标的维度行：订单数、成交笔数、买入量、卖出量。 */
    public record SymbolLine(long orderCount, long tradeCount, long buyQty, long sellQty) {
    }

    /** 一处对账差异：定位到账户（必）、标的（账户级差异为 null）、维度、两边取值。 */
    public record Difference(String accountId, String symbol, String dimension,
                             String left, String right) {
        @Override
        public String toString() {
            return "差异[账户=" + accountId
                    + (symbol == null ? "" : " 标的=" + symbol)
                    + " 维度=" + dimension + " 左=" + left + " 右=" + right + "]";
        }
    }

    /**
     * 从引擎状态构建摘要。由 {@code MatchingEngine.reconciliationDigest()} 在撮合锁内调用，
     * 保证摘要落在明确的指令切点（lastSeq）上，不夹在指令中间。
     */
    public static ReconciliationDigest build(long lastSeq,
                                             Map<String, Account> accounts,
                                             Collection<Order> orders,
                                             List<Trade> trades) {
        ReconciliationDigest d = new ReconciliationDigest();
        d.lastSeq = lastSeq;
        for (Map.Entry<String, Account> e : accounts.entrySet()) {
            Account a = e.getValue();
            d.accounts.put(e.getKey(), new AccountLine(
                    a.getCash().toPlainString(), a.getReservedCash().toPlainString(),
                    a.getPosition(), a.getReservedPosition()));
        }
        for (Order o : orders) {
            SymbolLine line = d.line(o.getAccountId(), o.getSymbol());
            d.putLine(o.getAccountId(), o.getSymbol(), new SymbolLine(
                    line.orderCount + 1, line.tradeCount, line.buyQty, line.sellQty));
        }
        for (Trade t : trades) {
            SymbolLine buy = d.line(t.buyerAccountId(), t.symbol());
            d.putLine(t.buyerAccountId(), t.symbol(), new SymbolLine(
                    buy.orderCount, buy.tradeCount + 1,
                    buy.buyQty + t.quantity(), buy.sellQty));
            SymbolLine sell = d.line(t.sellerAccountId(), t.symbol());
            d.putLine(t.sellerAccountId(), t.symbol(), new SymbolLine(
                    sell.orderCount, sell.tradeCount + 1,
                    sell.buyQty, sell.sellQty + t.quantity()));
        }
        return d;
    }

    private SymbolLine line(String accountId, String symbol) {
        return accountSymbols.getOrDefault(accountId, Map.of())
                .getOrDefault(symbol, new SymbolLine(0, 0, 0, 0));
    }

    private void putLine(String accountId, String symbol, SymbolLine line) {
        accountSymbols.computeIfAbsent(accountId, k -> new TreeMap<>()).put(symbol, line);
    }

    /** 确定性 JSON（同一状态两次导出逐字节相同）。 */
    public String toJson() {
        return JsonIO.toJson(this);
    }

    public static ReconciliationDigest fromJson(String json) {
        return JsonIO.fromJson(json, ReconciliationDigest.class);
    }

    /** 摘要指纹：对确定性 JSON 取 SHA-256，两边指纹相等即状态一致。 */
    public String hash() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(toJson().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("摘要哈希失败", e);
        }
    }

    /** 与另一份摘要逐项比对，返回全部差异（空列表 = 一致）。 */
    public List<Difference> diff(ReconciliationDigest other) {
        List<Difference> out = new ArrayList<>();
        if (lastSeq != other.lastSeq) {
            out.add(new Difference("*", null, "lastSeq",
                    String.valueOf(lastSeq), String.valueOf(other.lastSeq)));
        }
        for (String acct : union(accounts.keySet(), other.accounts.keySet())) {
            AccountLine l = accounts.get(acct);
            AccountLine r = other.accounts.get(acct);
            if (l == null || r == null) {
                out.add(new Difference(acct, null, "account",
                        l == null ? "<缺失>" : l.toString(),
                        r == null ? "<缺失>" : r.toString()));
                continue;
            }
            cmp(out, acct, null, "cash", l.cash(), r.cash());
            cmp(out, acct, null, "reservedCash", l.reservedCash(), r.reservedCash());
            cmp(out, acct, null, "position", l.position(), r.position());
            cmp(out, acct, null, "reservedPosition", l.reservedPosition(), r.reservedPosition());
        }
        for (String acct : union(accountSymbols.keySet(), other.accountSymbols.keySet())) {
            Map<String, SymbolLine> lm = accountSymbols.getOrDefault(acct, Map.of());
            Map<String, SymbolLine> rm = other.accountSymbols.getOrDefault(acct, Map.of());
            for (String sym : union(lm.keySet(), rm.keySet())) {
                SymbolLine l = lm.get(sym);
                SymbolLine r = rm.get(sym);
                if (l == null || r == null) {
                    out.add(new Difference(acct, sym, "symbol",
                            l == null ? "<缺失>" : l.toString(),
                            r == null ? "<缺失>" : r.toString()));
                    continue;
                }
                cmp(out, acct, sym, "orderCount", l.orderCount(), r.orderCount());
                cmp(out, acct, sym, "tradeCount", l.tradeCount(), r.tradeCount());
                cmp(out, acct, sym, "buyQty", l.buyQty(), r.buyQty());
                cmp(out, acct, sym, "sellQty", l.sellQty(), r.sellQty());
            }
        }
        return out;
    }

    private static java.util.Set<String> union(java.util.Set<String> a,
                                               java.util.Set<String> b) {
        java.util.Set<String> u = new java.util.TreeSet<>(a);
        u.addAll(b);
        return u;
    }

    private static void cmp(List<Difference> out, String acct, String sym,
                            String dim, Object l, Object r) {
        if (!Objects.equals(l, r)) {
            out.add(new Difference(acct, sym, dim, String.valueOf(l), String.valueOf(r)));
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ReconciliationDigest other
                && lastSeq == other.lastSeq
                && accounts.equals(other.accounts)
                && accountSymbols.equals(other.accountSymbols);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lastSeq, accounts, accountSymbols);
    }
}
