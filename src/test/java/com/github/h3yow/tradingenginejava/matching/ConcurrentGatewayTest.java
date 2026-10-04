package com.github.h3yow.tradingenginejava.matching;

import com.github.h3yow.tradingenginejava.matching.engine.MatchingEngine;
import com.github.h3yow.tradingenginejava.matching.engine.RiskConfig;
import com.github.h3yow.tradingenginejava.matching.gateway.DeterministicGateway;
import com.github.h3yow.tradingenginejava.matching.model.Account;
import com.github.h3yow.tradingenginejava.matching.model.Command;
import com.github.h3yow.tradingenginejava.matching.model.CommandResult;
import com.github.h3yow.tradingenginejava.matching.model.RejectReason;
import com.github.h3yow.tradingenginejava.matching.model.Side;
import com.github.h3yow.tradingenginejava.matching.model.Trade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发竞争单测：同一批委托在不同并发调度（不同 shuffle 种子 + 多线程栅栏同时起跑）下，
 * 成交序列、余额、拒绝集合必须逐字节级一致；不得重复成交、超卖、负余额。
 */
class ConcurrentGatewayTest {

    /** 一次确定性场景的完整指纹。 */
    private record Fingerprint(String trades, String accounts, String rejects, String orderStates) {
    }

    private Fingerprint runScenario(Long shuffleSeed, int accounts, int commandsEach) {
        RiskConfig risk = new RiskConfig(50, new BigDecimal("10000000"));
        MatchingEngine engine = new MatchingEngine(risk);
        for (int i = 0; i < accounts; i++) {
            engine.registerAccount("acct" + i, new BigDecimal("1000000"), 10_000);
        }

        long seq = 0;
        List<Command> batch = new ArrayList<>();
        for (int i = 0; i < accounts; i++) {
            String me = "acct" + i;
            // 每个账户交替挂自己的买/卖（不与自身对手盘同账户，避免自成交拒绝主导场景），
            // 对手盘来自其他账户
            for (int k = 0; k < commandsEach; k++) {
                long px = 100 + (k % 5);
                if (k % 2 == 0) {
                    batch.add(Command.placeLimit(++seq, me, "BTC",
                            me + "-s" + k, Side.SELL, new BigDecimal(px), 3));
                } else {
                    batch.add(Command.placeLimit(++seq, me, "BTC",
                            me + "-b" + k, Side.BUY, new BigDecimal(px + 4), 3));
                }
            }
        }

        System.out.println("【输入】并发批次 " + batch.size() + " 条，shuffleSeed=" + shuffleSeed
                + "，线程栅栏同时起跑，实际进入顺序由 JVM 调度决定");
        DeterministicGateway gw = new DeterministicGateway(engine);
        Map<Long, CommandResult> results = gw.submitBatch(batch, shuffleSeed);
        System.out.println("【判定】拒绝数=" + engine.getRejections().size()
                + " 成交数=" + engine.getTrades().size());

        StringBuilder trades = new StringBuilder();
        long totalQty = 0;
        for (Trade t : engine.getTrades()) {
            trades.append(t.seq()).append(':').append(t.tradeId()).append(':')
                    .append(t.makerClientOrderId()).append('>')
                    .append(t.takerClientOrderId()).append('@')
                    .append(t.price().toPlainString()).append('x').append(t.quantity()).append(';');
            totalQty += t.quantity();
        }
        StringBuilder accs = new StringBuilder();
        for (int i = 0; i < accounts; i++) {
            Account a = engine.getAccount("acct" + i);
            assertTrue(a.getCash().signum() >= 0, "现金不得为负: " + a);
            assertTrue(a.getReservedCash().signum() >= 0, "冻结现金不得为负: " + a);
            assertTrue(a.getPosition() >= 0, "持仓不得为负: " + a);
            assertTrue(a.getReservedPosition() >= 0, "冻结持仓不得为负: " + a);
            // 恒等式
            assertEquals(0, a.getCash().compareTo(a.getAvailableCash().add(a.getReservedCash())),
                    "cash 恒等式: " + a);
            assertEquals(a.getPosition(), a.getAvailablePosition() + a.getReservedPosition(),
                    "position 恒等式: " + a);
            accs.append(a).append('|');
        }
        // 全局持仓守恒：总量不变
        long totalPosition = 0;
        BigDecimal totalCash = BigDecimal.ZERO;
        for (int i = 0; i < accounts; i++) {
            totalPosition += engine.getAccount("acct" + i).getPosition();
            totalCash = totalCash.add(engine.getAccount("acct" + i).getCash());
        }
        assertEquals(accounts * 10_000L, totalPosition, "全局持仓守恒");
        assertEquals(0, totalCash.compareTo(new BigDecimal(accounts * 1_000_000L)),
                "全局现金守恒（只是账户间转移）");

        StringBuilder rejects = new StringBuilder();
        for (var r : engine.getRejections()) {
            rejects.append(r.seq()).append(':').append(r.reason()).append(';');
        }
        StringBuilder states = new StringBuilder();
        results.keySet().stream().sorted().forEach(k -> {
            CommandResult r = results.get(k);
            states.append(k).append('=').append(r.accepted()
                    ? r.finalStatus() + "/open" + r.remainingQty()
                    : "REJ:" + r.rejection().reason()).append(';');
        });
        System.out.println("【结果】总成交量=" + totalQty);
        return new Fingerprint(trades.toString(), accs.toString(), rejects.toString(),
                states.toString());
    }

    @Test
    @DisplayName("不同并发调度下成交序列/余额/拒绝完全一致，且无重复成交")
    void scheduleIndependence() {
        Fingerprint f1 = runScenario(1L, 6, 12);
        Fingerprint f2 = runScenario(2L, 6, 12);
        Fingerprint f3 = runScenario(null, 6, 12);
        System.out.println("【比对】三次运行（不同调度）指纹:");
        System.out.println("  tradesHash=" + f1.trades().hashCode()
                + "/" + f2.trades().hashCode() + "/" + f3.trades().hashCode());
        assertEquals(f1, f2, "shuffle 种子 1 vs 2 结果不一致");
        assertEquals(f1, f3, "shuffle vs 顺序派发结果不一致");
    }

    @Test
    @DisplayName("同批重复 seq 与超限委托必须显式拒绝，绝不静默丢弃")
    void duplicatesAndLimitsAreExplicit() {
        MatchingEngine engine = new MatchingEngine(new RiskConfig(5, new BigDecimal("10000000")));
        engine.registerAccount("x", new BigDecimal("1000000"), 10_000);
        engine.registerAccount("y", new BigDecimal("1000000"), 10_000);

        List<Command> batch = new ArrayList<>();
        // seq1..5：x 挂 5 个高价卖单，占满在途限额（y 没有任何买盘，不会成交）
        for (long k = 1; k <= 5; k++) {
            batch.add(Command.placeLimit(k, "x", "BTC", "x" + k,
                    Side.SELL, new BigDecimal(200 + k), 1));
        }
        // seq=6 到达两条不同指令：排序首条进入引擎（也会超限），另一条为同 seq 重复
        batch.add(Command.placeLimit(6, "y", "BTC", "yRest",
                Side.BUY, new BigDecimal("10"), 1)); // y 的低价买单，不成交
        batch.add(Command.placeLimit(6, "x", "BTC", "xDup",
                Side.SELL, new BigDecimal("210"), 1)); // 与上一条同 seq=6
        // seq=7：x 已有 5 个在途卖单 -> 超限
        batch.add(Command.placeLimit(7, "x", "BTC", "xOver",
                Side.SELL, new BigDecimal("211"), 1));

        Map<Long, CommandResult> r = new DeterministicGateway(engine).submitBatch(batch, 99L);
        System.out.println("【输入】seq=6 到达两条：yRest 与 xDup（同 seq 重复）");
        System.out.println("【判定】保留的 seq=6 accepted=" + r.get(6L).accepted()
                + " 原因=" + (r.get(6L).rejection() == null ? "-" : r.get(6L).rejection().reason()));
        // xDup 从未进入引擎，不产生任何成交/委托
        assertTrue(engine.getOrder("xDup") == null, "重复 seq 指令不得进入引擎");
        assertFalse(engine.getTrades().stream()
                .anyMatch(t -> t.takerClientOrderId().equals("xDup")
                        || t.makerClientOrderId().equals("xDup")));
        // seq=7 明确超限拒绝而非静默丢弃
        assertFalse(r.get(7L).accepted());
        assertEquals(RejectReason.LIMIT_EXCEEDED, r.get(7L).rejection().reason());
        System.out.println("【判定】seq=7 返回 LIMIT_EXCEEDED，重复 seq=6 的 xDup 未成交");
    }

    @Test
    @DisplayName("并发交叉撮合必须真实成交：交替买卖在不同调度下产生同一批成交，非只有拒绝")
    void concurrentCrossingOrdersActuallyTradeDeterministically() {
        int accounts = 30;

        String fp1 = crossingRun(accounts, 7L);
        String fp2 = crossingRun(accounts, 99L);
        String fp3 = crossingRun(accounts, null);
        // 三个不同并发调度（含乱序 shuffle）指纹一致
        assertEquals(fp1, fp2, "不同 shuffle 种子下成交序列不一致");
        assertEquals(fp1, fp3, "乱序与顺序派发结果不一致");
    }

    /**
     * 构造必然交叉的并发场景：卖方与买方在同一可成交价区间交错下单，
     * 所有指令乱序到达，但 seq 决定撮合结果。
     * 返回“成交编号 -> 双方委托/价/量”的确定性指纹，并断言确实跑出了成交。
     */
    private String crossingRun(int accounts, Long seed) {
        RiskConfig risk = new RiskConfig(200, new BigDecimal("1000000000"));
        MatchingEngine engine = new MatchingEngine(risk);
        for (int i = 0; i < accounts; i++) {
            engine.registerAccount("t" + i, new BigDecimal("10000000"), 100_000);
        }
        long seq = 0;
        List<Command> batch = new ArrayList<>();
        // 卖方（偶数账户）在 100 附近挂卖，买方（奇数账户）在 100 上方挂买，必然交叉
        for (int i = 0; i < accounts; i++) {
            if (i % 2 == 0) {
                batch.add(Command.placeLimit(++seq, "t" + i, "BTC",
                        "t" + i + "-s", Side.SELL, new BigDecimal("100"), 8));
            } else {
                batch.add(Command.placeLimit(++seq, "t" + i, "BTC",
                        "t" + i + "-b", Side.BUY, new BigDecimal("101"), 3));
            }
        }
        // 第二轮：买方加量（部分成交）、卖市价（与挂买单成交），制造跨轮真实成交
        for (int i = 0; i < accounts; i++) {
            if (i % 2 == 0) {
                batch.add(Command.placeMarket(++seq, "t" + i, "BTC",
                        "t" + i + "-ms", Side.SELL, 2));
            } else {
                batch.add(Command.placeLimit(++seq, "t" + i, "BTC",
                        "t" + i + "-b2", Side.BUY, new BigDecimal("101"), 7));
            }
        }

        System.out.println("【输入】" + accounts + " 账户并发交叉委托 " + batch.size()
                + " 条，shuffleSeed=" + seed + "，栅栏同时起跑");
        Map<Long, CommandResult> results =
                new DeterministicGateway(engine).submitBatch(batch, seed);

        long tradeQty = engine.getTrades().stream().mapToLong(Trade::quantity).sum();
        long tradeCount = engine.getTrades().size();
        System.out.println("【判定】真实成交笔数=" + tradeCount + " 成交量=" + tradeQty
                + " 拒绝数=" + engine.getRejections().size());
        assertTrue(tradeCount >= accounts / 2,
                "并发场景必须真实跑出成交，实际只有 " + tradeCount + " 笔");
        assertTrue(tradeQty > 0, "必须有真实成交量");

        // 每个账户四分量非负 + 恒等式 + 全局守恒
        BigDecimal totalCash = BigDecimal.ZERO;
        long totalPosition = 0;
        for (int i = 0; i < accounts; i++) {
            Account a = engine.getAccount("t" + i);
            assertTrue(a.getCash().signum() >= 0 && a.getReservedCash().signum() >= 0
                            && a.getPosition() >= 0 && a.getReservedPosition() >= 0,
                    "分量不得为负: " + a);
            assertEquals(0, a.getCash().compareTo(
                    a.getAvailableCash().add(a.getReservedCash())));
            assertEquals(a.getPosition(),
                    a.getAvailablePosition() + a.getReservedPosition());
            totalCash = totalCash.add(a.getCash());
            totalPosition += a.getPosition();
        }
        assertEquals(0, totalCash.compareTo(new BigDecimal(accounts * 10_000_000L)),
                "全局现金守恒");
        assertEquals(accounts * 100_000L, totalPosition, "全局持仓守恒");

        StringBuilder fp = new StringBuilder();
        for (Trade t : engine.getTrades()) {
            fp.append(t.tradeId()).append(':').append(t.takerSide())
                    .append(t.makerClientOrderId()).append('>')
                    .append(t.takerClientOrderId()).append('@')
                    .append(t.price().toPlainString()).append('x')
                    .append(t.quantity()).append(';');
        }
        fp.append('|');
        results.forEach((k, r) -> fp.append(k).append('=')
                .append(r.accepted() ? r.finalStatus() + "/o" + r.remainingQty()
                        : "R:" + r.rejection().reason()).append(';'));
        System.out.println("【结果】指纹 hash=" + fp.toString().hashCode()
                + "，成交序列对调度不敏感");
        return fp.toString();
    }
}
