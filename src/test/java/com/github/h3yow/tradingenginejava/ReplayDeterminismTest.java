package com.github.h3yow.tradingenginejava;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.tradingenginejava.command.Command;
import com.github.h3yow.tradingenginejava.command.CommandResult;
import com.github.h3yow.tradingenginejava.engine.EngineSnapshot;
import com.github.h3yow.tradingenginejava.engine.MatchingEngine;
import com.github.h3yow.tradingenginejava.engine.ReplayEngine;
import com.github.h3yow.tradingenginejava.model.OrderStatus;
import com.github.h3yow.tradingenginejava.model.OrderType;
import com.github.h3yow.tradingenginejava.model.RejectReason;
import com.github.h3yow.tradingenginejava.model.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回放确定性测试：
 *  - 同一命令日志重复回放，成交/余额/快照完全一致；
 *  - 从快照（含 JSON 序列化往返）恢复后续跑，不产生重复成交、状态不漂移；
 *  - 恢复后旧 seq 命令被明确拒绝（INVALID_SEQUENCE），新 seq 正常续号。
 */
class ReplayDeterminismTest {

    private static final Logger log = LoggerFactory.getLogger(ReplayDeterminismTest.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final String SYM = "ETH";

    private List<Command> firstPhase() {
        return List.of(
                Command.deposit(1, "A", 500_000L, SYM, 0L),
                Command.deposit(2, "B", 0L, SYM, 300L),
                Command.deposit(3, "C", 500_000L, SYM, 0L),
                Command.place(4, "B", "s1", SYM, Side.SELL, OrderType.LIMIT, 100, 40),
                Command.place(5, "A", "b1", SYM, Side.BUY, OrderType.LIMIT, 100, 60), // 成交40剩20挂簿
                Command.place(6, "C", "b2", SYM, Side.BUY, OrderType.LIMIT, 90, 20),  // 不成交挂簿
                Command.cancel(7, "A", "b1")                                         // 撤 b1 剩余买单20
        );
    }

    private List<Command> secondPhase() {
        return List.of(
                Command.deposit(8, "D", 0L, SYM, 200L),
                Command.place(9, "D", "s2", SYM, Side.SELL, OrderType.LIMIT, 90, 20), // 与 b2 成交
                Command.place(10, "A", "b3", SYM, Side.BUY, OrderType.LIMIT, 90, 40) // s2只20，成交20剩20挂簿
        );
    }

    private String canonical(EngineSnapshot s) {
        return s.nextSeq() + "|" + s.nextTradeSeq() + "|"
                + s.trades().size() + "|"
                + s.accounts().toString() + "|"
                + s.allOrders().keySet() + "|"
                + s.activeOrders().stream().map(o -> o.getOrderId() + ":" + o.getStatus()
                        + ":" + o.getFilledQty() + ":" + o.getRemainingQty()).toList();
    }

    @Test
    @DisplayName("重复回放：同一份命令日志回放3次，结果指纹逐字节相同")
    void repeatReplayIsIdentical() throws Exception {
        List<Command> commands = firstPhase();
        String first = null;
        for (int i = 0; i < 3; i++) {
            ReplayEngine.ReplayOutcome out = ReplayEngine.replay(commands);
            String fp = canonical(out.snapshot());
            log.info("[重复回放] round={} 成交={} 指纹hash={}",
                    i + 1, out.snapshot().trades().size(), fp.hashCode());
            if (first == null) {
                first = fp;
            } else {
                assertEquals(first, fp, "第 " + (i + 1) + " 次回放必须与第一次完全一致");
            }
            // JSON 序列化往返也必须等价
            String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out.snapshot());
            EngineSnapshot back = JSON.readValue(json, EngineSnapshot.class);
            assertEquals(canonical(out.snapshot()), canonical(back),
                    "快照 JSON 序列化往返不得漂移");
        }
    }

    @Test
    @DisplayName("快照恢复：从中途快照恢复后续跑，无重复成交、状态与一次性回放一致")
    void restoreThenContinue() {
        // 1) 执行第一阶段并导出快照
        MatchingEngine phase1 = new MatchingEngine();
        phase1.submitBatch(firstPhase());
        EngineSnapshot snap1 = phase1.snapshot();
        long tradesAtSplit = snap1.trades().size();
        log.info("[快照恢复] 断点成交数={} 快照nextTradeSeq={}", tradesAtSplit, snap1.nextTradeSeq());
        assertEquals(1, tradesAtSplit);

        // 2) 恢复后执行第二阶段
        ReplayEngine.ReplayOutcome cont = ReplayEngine.replayFrom(snap1, secondPhase());
        long totalTradesViaRestore = cont.snapshot().trades().size();
        log.info("[快照恢复] 恢复后续跑完成交={}", totalTradesViaRestore);

        // 3) 一次性完整回放作为对照
        List<Command> all = new java.util.ArrayList<>(firstPhase());
        all.addAll(secondPhase());
        ReplayEngine.ReplayOutcome whole = ReplayEngine.replay(all);

        assertEquals(canonical(whole.snapshot()), canonical(cont.snapshot()),
                "断点恢复后续跑的最终状态必须与一次性完整回放一致");
        assertEquals(whole.snapshot().trades().size(), totalTradesViaRestore,
                "恢复不得产生重复成交");
        // 恢复后历史成交仍在（用于审计），但每笔 tradeSeq 唯一
        long distinct = cont.snapshot().trades().stream()
                .mapToLong(t -> t.tradeSeq()).distinct().count();
        assertEquals(totalTradesViaRestore, distinct);

        // 4) 状态自洽性检查
        // s1 的 40 全部卖给 b1；b1 成交 40 后剩余 20 被 seq7 撤单
        assertEquals(OrderStatus.FILLED, cont.snapshot().allOrders().get("s1").getStatus());
        var b1 = cont.snapshot().allOrders().get("b1");
        assertEquals(40, b1.getFilledQty());
        assertEquals(20, b1.getRemainingQty());
        assertEquals(OrderStatus.CANCELLED, b1.getStatus());
        // b2 与 b3 都在 90：b2(seq6) 时间优先，s2 的 20 全部与 b2 成交；b3 剩 40 挂簿
        assertEquals(OrderStatus.FILLED, cont.snapshot().allOrders().get("b2").getStatus());
        var b3 = cont.snapshot().allOrders().get("b3");
        assertEquals(0, b3.getFilledQty());
        assertEquals(40, b3.getRemainingQty());
        assertEquals(OrderStatus.NEW, b3.getStatus());
    }

    @Test
    @DisplayName("恢复后旧序号被拒、新序号正常，成交序号从快照 nextTradeSeq 续号")
    void restoreSeqGuard() {
        MatchingEngine phase1 = new MatchingEngine();
        phase1.submitBatch(firstPhase());
        EngineSnapshot snap = phase1.snapshot();
        long nextTrade = snap.nextTradeSeq();

        MatchingEngine restored = MatchingEngine.restore(snap);
        // 旧 seq（=5）必须被拒，不能重放
        CommandResult stale = restored.submit(
                Command.cancel(5, "A", "b1"));
        assertFalse(stale.accepted());
        assertEquals(RejectReason.INVALID_SEQUENCE, stale.reason());
        log.info("[序号守卫] 旧seq命令被拒 reason={} basis={}", stale.reason(), stale.message());
        // 历史成交不被重复执行
        assertEquals(snap.trades().size(), restored.getTrades().size());
        // 新 seq 正常：D 开户后挂卖单，与簿中 b2@90 成交，成交号从快照续接
        CommandResult dep = restored.submit(Command.deposit(8, "D", 0L, SYM, 20L));
        assertTrue(dep.accepted());
        CommandResult fresh = restored.submit(
                Command.place(9, "D", "sx", SYM, Side.SELL, OrderType.LIMIT, 90, 20));
        assertTrue(fresh.accepted(), fresh.message());
        long maxTradeSeq = restored.getTrades().stream()
                .mapToLong(t -> t.tradeSeq()).max().orElseThrow();
        assertEquals(nextTrade, maxTradeSeq, "恢复后成交序号必须从 nextTradeSeq 续号");
        log.info("[序号守卫] 新成交序号={} 与快照 nextTradeSeq={} 续接一致", maxTradeSeq, nextTrade);
    }
}
