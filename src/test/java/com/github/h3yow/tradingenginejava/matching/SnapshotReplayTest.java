package com.github.h3yow.tradingenginejava.matching;

import com.github.h3yow.tradingenginejava.matching.engine.MatchingEngine;
import com.github.h3yow.tradingenginejava.matching.model.Account;
import com.github.h3yow.tradingenginejava.matching.model.Command;
import com.github.h3yow.tradingenginejava.matching.model.CommandResult;
import com.github.h3yow.tradingenginejava.matching.model.OrderStatus;
import com.github.h3yow.tradingenginejava.matching.model.RejectReason;
import com.github.h3yow.tradingenginejava.matching.model.Side;
import com.github.h3yow.tradingenginejava.matching.model.Trade;
import com.github.h3yow.tradingenginejava.matching.replay.ReplaySupport;
import com.github.h3yow.tradingenginejava.matching.snapshot.Snapshot;
import com.github.h3yow.tradingenginejava.matching.util.JsonIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快照与确定性回放单测：
 * 1) 快照 JSON 可往返（toJson/fromJson），恢复后账户/订单簿/成交/拒绝逐项一致；
 * 2) 同一事件日志重复回放，不产生重复成交、不污染拒绝；
 * 3) 从快照恢复 + 增量日志回放 = 从头完整应用，成交序列与余额完全可复现；
 * 4) 从快照恢复后对已处理指令的撤改仍判定 UNKNOWN_ORDER（历史一致）。
 */
class SnapshotReplayTest {

    private void seedAccounts(MatchingEngine e) {
        e.registerAccount("A", new BigDecimal("100000"), 1000);
        e.registerAccount("B", new BigDecimal("100000"), 1000);
        e.registerAccount("C", new BigDecimal("500"), 10);
    }

    private List<Command> buildLog() {
        List<Command> log = new ArrayList<>();
        long s = 0;
        log.add(Command.placeLimit(++s, "A", "BTC", "s1",
                Side.SELL, new BigDecimal("100"), 5));
        log.add(Command.placeLimit(++s, "B", "BTC", "b1",
                Side.BUY, new BigDecimal("100"), 2));   // 成交2，s1 剩3
        log.add(Command.placeLimit(++s, "C", "BTC", "m1",
                Side.BUY, new BigDecimal("100"), 10));  // 资金不足 -> INSUFFICIENT_CASH
        log.add(Command.placeMarket(++s, "C", "BTC", "m2",
                Side.BUY, 10));                          // 500元买5股，余量IOC撤
        log.add(Command.cancel(++s, "A", "s1"));         // 撤剩余
        log.add(Command.cancel(++s, "A", "s1"));         // 重复撤 -> UNKNOWN_ORDER
        log.add(Command.placeLimit(++s, "A", "BTC", "s2",
                Side.SELL, new BigDecimal("99"), 4));
        log.add(Command.placeLimit(++s, "B", "BTC", "b2",
                Side.BUY, new BigDecimal("99"), 4));    // 成交4
        return log;
    }

    private String fingerprint(MatchingEngine e) {
        StringBuilder sb = new StringBuilder();
        for (Trade t : e.getTrades()) {
            sb.append(t.tradeId()).append(':').append(t.makerClientOrderId())
                    .append('>').append(t.takerClientOrderId())
                    .append('@').append(t.price().toPlainString())
                    .append('x').append(t.quantity()).append(';');
        }
        sb.append('|');
        for (String a : List.of("A", "B", "C")) {
            Account acc = e.getAccount(a);
            sb.append(a).append(":cash=").append(acc.getCash().toPlainString())
                    .append(",resv=").append(acc.getReservedCash().toPlainString())
                    .append(",pos=").append(acc.getPosition())
                    .append(",resvPos=").append(acc.getReservedPosition()).append(';');
        }
        sb.append('|');
        e.getRejections().forEach(r -> sb.append(r.seq()).append(':')
                .append(r.reason()).append(';'));
        return sb.toString();
    }

    @Test
    @DisplayName("快照 JSON 往返后状态逐项一致（余额/成交/拒绝/委托状态）")
    void snapshotRoundTrip() {
        MatchingEngine e = new MatchingEngine();
        seedAccounts(e);
        for (Command c : buildLog()) {
            CommandResult r = e.apply(c);
            System.out.println("【输入】" + c + " => 【判定】" + r);
        }
        String before = fingerprint(e);
        String json = JsonIO.toJson(e.snapshot());
        System.out.println("【快照】JSON 长度=" + json.length() + "，内容片段:");
        System.out.println(json.substring(0, Math.min(600, json.length())) + "...");

        MatchingEngine restored = new MatchingEngine();
        seedAccounts(restored);
        Snapshot snap = JsonIO.fromJson(json, Snapshot.class);
        restored.restore(snap);
        // restore 自带账户信息，无需再注册；这里验证恢复后的查询
        String after = fingerprint(restored);
        assertEquals(before, after, "恢复后指纹不一致");

        // 快照 JSON 确定性：同一状态再导出一次，文本逐字节相同
        String json2 = JsonIO.toJson(restored.snapshot());
        assertEquals(json, json2, "快照序列化不确定（键序/内容漂移）");
        System.out.println("【结果】恢复后指纹一致，快照 JSON 逐字节可复现");
    }

    @Test
    @DisplayName("重复回放整段日志：不重复成交、不新增拒绝，状态与一次执行一致")
    void replayIsIdempotent() {
        MatchingEngine ref = new MatchingEngine();
        seedAccounts(ref);
        List<Command> log = buildLog();
        log.forEach(ref::apply);
        String refFp = fingerprint(ref);
        int refTrades = ref.getTrades().size();
        int refRejects = ref.getRejections().size();

        MatchingEngine replay = new MatchingEngine();
        seedAccounts(replay);
        List<CommandResult> first = ReplaySupport.replay(replay, log);
        System.out.println("【输入】首次回放 " + log.size() + " 条，实际应用 " + first.size());
        assertEquals(log.size(), first.size());
        assertEquals(refFp, fingerprint(replay));

        // 再回放两次：seq<=lastSeq 全部跳过
        List<CommandResult> second = ReplaySupport.replay(replay, log);
        List<CommandResult> third = ReplaySupport.replay(replay, log);
        System.out.println("【判定】第二/三次回放实际应用数="
                + second.size() + "/" + third.size());
        assertEquals(0, second.size());
        assertEquals(0, third.size());
        assertEquals(refTrades, replay.getTrades().size(), "重复回放造成重复成交");
        assertEquals(refRejects, replay.getRejections().size(), "重复回放污染拒绝日志");
        assertEquals(refFp, fingerprint(replay), "重复回放后状态漂移");
    }

    @Test
    @DisplayName("快照恢复 + 增量日志回放 == 从头执行（成交序列与余额可复现）")
    void snapshotThenIncrementalReplay() {
        List<Command> log = buildLog();
        int cut = 4; // 在前4条后打快照，后4条作为增量日志

        MatchingEngine full = new MatchingEngine();
        seedAccounts(full);
        log.forEach(full::apply);
        String fullFp = fingerprint(full);

        MatchingEngine prefix = new MatchingEngine();
        seedAccounts(prefix);
        log.subList(0, cut).forEach(prefix::apply);
        Snapshot snap = prefix.snapshot();
        String json = JsonIO.toJson(snap);

        // 全新空引擎：从 JSON 快照恢复，再回放“完整日志”（前半段应被跳过）
        MatchingEngine rebuilt = new MatchingEngine();
        rebuilt.restore(JsonIO.fromJson(json, Snapshot.class));
        List<CommandResult> applied = ReplaySupport.replay(rebuilt, log);
        System.out.println("【输入】快照水位 lastSeq=" + snap.lastSeq
                + "，回放完整日志 " + log.size() + " 条");
        System.out.println("【判定】仅增量应用 " + applied.size() + " 条（历史部分跳过）");
        assertEquals(log.size() - cut, applied.size());
        assertEquals(fullFp, fingerprint(rebuilt), "快照+增量回放与全量执行不一致");

        // 恢复后已终结委托的撤改仍为 UNKNOWN_ORDER（历史判定一致）
        CommandResult ghost = rebuilt.apply(Command.cancel(9L, "A", "s1"));
        assertFalse(ghost.accepted());
        assertEquals(RejectReason.UNKNOWN_ORDER, ghost.rejection().reason());

        // 恢复后继续撮合：s2 卖单已被 b2 吃光，新买单应无流动性
        CommandResult noLiq = rebuilt.apply(Command.placeMarket(10L, "A", "BTC", "m9",
                Side.BUY, 1));
        assertEquals(RejectReason.MARKET_NO_LIQUIDITY, noLiq.rejection().reason());
        System.out.println("【结果】全量指纹=");
        System.out.println(fullFp);
        assertTrue(true);
    }

    @Test
    @DisplayName("部分成交中的挂单经快照恢复后，继续撮合保持价格/时间优先与状态自洽")
    void restingOrderSurvivesSnapshotAndKeepsMatching() {
        MatchingEngine e = new MatchingEngine();
        seedAccounts(e);
        // s1 卖5@100，b1 买2 -> s1 剩3 挂簿
        e.apply(Command.placeLimit(1, "A", "BTC", "s1",
                Side.SELL, new BigDecimal("100"), 5));
        e.apply(Command.placeLimit(2, "B", "BTC", "b1",
                Side.BUY, new BigDecimal("100"), 2));
        Snapshot mid = e.snapshot();

        MatchingEngine r = new MatchingEngine();
        r.restore(mid);
        assertEquals(3, r.getOrder("s1").getOpenQty());
        assertEquals(OrderStatus.PARTIALLY_FILLED, r.getOrder("s1").getStatus());
        assertEquals(3, r.getAccount("A").getReservedPosition());

        // 恢复后继续买3，恰好吃掉剩余 s1
        CommandResult cont = r.apply(Command.placeLimit(3, "B", "BTC", "b2",
                Side.BUY, new BigDecimal("100"), 3));
        assertEquals(OrderStatus.FILLED, cont.finalStatus());
        assertEquals(1, cont.trades().size());
        assertEquals(3, cont.trades().get(0).quantity());
        assertEquals(OrderStatus.FILLED, r.getOrder("s1").getStatus());
        assertEquals(0, r.getAccount("A").getReservedPosition());
        System.out.println("【结果】恢复后续撮合成交=" + cont.trades().get(0)
                + " 卖方状态=" + r.getOrder("s1"));
    }
}
