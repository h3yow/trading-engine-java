package com.github.h3yow.tradingenginejava.matching;

import com.github.h3yow.tradingenginejava.matching.engine.MatchingEngine;
import com.github.h3yow.tradingenginejava.matching.model.BatchResult;
import com.github.h3yow.tradingenginejava.matching.model.Command;
import com.github.h3yow.tradingenginejava.matching.model.CommandResult;
import com.github.h3yow.tradingenginejava.matching.model.OrderStatus;
import com.github.h3yow.tradingenginejava.matching.model.RejectReason;
import com.github.h3yow.tradingenginejava.matching.model.Side;
import com.github.h3yow.tradingenginejava.matching.reconcile.Reconciler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 委托组原子提交单测。
 *
 * <p>覆盖：整组成功时逐条结果可查；组内任意一条被拒则整组回滚、对外零残留；
 * “整组没落地”（BATCH_REJECTED）与“某一条本身有问题”（成员拒绝原因）可区分；
 * 回滚后 seq 可重新使用；以及并发环境下原子组不被其他批次穿插。
 */
class AtomicBatchTest {

    private MatchingEngine engine() {
        MatchingEngine e = new MatchingEngine();
        e.registerAccount("A", new BigDecimal("100000"), 1000);
        e.registerAccount("B", new BigDecimal("100000"), 1000);
        e.registerAccount("C", new BigDecimal("500"), 20);
        return e;
    }

    private void log(String tag, Object o) {
        System.out.println("【" + tag + "】" + o);
    }

    @Test
    @DisplayName("整组成功：组内按序撮合，每条委托各自的成交结果可单独查出")
    void successfulBatchKeepsPerOrderResults() {
        MatchingEngine e = engine();
        // 盘前挂一个卖方：A 卖 10@100
        e.apply(Command.placeLimit(0, "A", "BTC", "seedSell",
                Side.SELL, new BigDecimal("100"), 10));

        List<Command> group = List.of(
                // B 买4 -> 与 seedSell 成交4
                Command.placeLimit(1, "B", "BTC", "b1",
                        Side.BUY, new BigDecimal("100"), 4),
                // C 市价买5：500 元买5
                Command.placeMarket(2, "C", "BTC", "m1", Side.BUY, 10),
                // B 再挂买1@90（无成交，挂簿冻结90）
                Command.placeLimit(3, "B", "BTC", "b2",
                        Side.BUY, new BigDecimal("90"), 1)
        );
        BatchResult result = e.applyAtomicBatch(group);
        log("判定", result.committed()
                ? "整组提交成功，返回 " + result.results().size() + " 条逐单结果"
                : result);
        assertTrue(result.committed(), () -> "整组应成功: " + result);
        assertEquals(1L, result.firstSeq());
        assertEquals(3, result.results().size());

        // 逐条核对各自的撮合结果
        CommandResult r1 = result.results().get(0);
        assertEquals("b1", r1.clientOrderId());
        assertEquals(OrderStatus.FILLED, r1.finalStatus());
        assertEquals(1, r1.trades().size());
        assertEquals(4, r1.trades().get(0).quantity());

        CommandResult r2 = result.results().get(1);
        assertEquals("m1", r2.clientOrderId());
        assertEquals(OrderStatus.CANCELED, r2.finalStatus()); // 市价余量 IOC 撤销
        assertEquals(5, r2.trades().get(0).quantity());
        assertEquals(0, r2.remainingQty());

        CommandResult r3 = result.results().get(2);
        assertEquals("b2", r3.clientOrderId());
        assertEquals(OrderStatus.NEW, r3.finalStatus());
        assertEquals(1, r3.remainingQty());

        assertEquals(3, e.getLastSeq(), "水位应推进到组末 seq=3");
        assertEquals(new BigDecimal("90"), e.getAccount("B").getReservedCash());
        log("结果", "seedSell 成交9剩1挂簿，B 冻结90，C 持仓25");
    }

    @Test
    @DisplayName("整组回滚：组内第2条非法时，第1条已产生的成交/冻结/挂单/水位全部零残留")
    void batchRollsBackEverythingWhenOneMemberRejected() {
        MatchingEngine e = engine();
        e.apply(Command.placeLimit(0, "A", "BTC", "seedSell",
                Side.SELL, new BigDecimal("100"), 10));
        // 回滚前基准状态
        var before = Reconciler.summarize(e);
        long tradesBefore = e.getTrades().size();

        List<Command> group = List.of(
                // 第1条：会立刻成交4（若没有原子性，这4股会残留）
                Command.placeLimit(1, "B", "BTC", "b1",
                        Side.BUY, new BigDecimal("100"), 4),
                // 第2条：C 现金500 却买10@100 -> INSUFFICIENT_CASH，整组连带回滚
                Command.placeLimit(2, "C", "BTC", "poorBuy",
                        Side.BUY, new BigDecimal("100"), 10)
        );
        BatchResult result = e.applyAtomicBatch(group);
        log("输入", "第1条买4会成交，第2条资金不足");
        log("判定", result.committed() ? "不应成功" :
                "整组回滚；成员原因=" + result.memberRejection().reason()
                        + " 明细=" + result.memberRejection().detail());

        assertFalse(result.committed());
        assertNull(result.batchRejection(), "这是成员被拒而非批次级非法");
        assertEquals(RejectReason.INSUFFICIENT_CASH,
                result.memberRejection().reason());
        assertEquals(2L, result.memberRejection().seq());

        // 对外零残留：订单、成交、冻结、挂单、水位、编号都回到提交前
        assertNull(e.getOrder("b1"), "第1条委托不得残留");
        assertNull(e.getOrder("poorBuy"));
        assertEquals(tradesBefore, e.getTrades().size(), "回滚后不得残留成交");
        assertEquals(0L, e.getLastSeq(), "水位回退到组前");
        assertEquals(BigDecimal.ZERO, e.getAccount("B").getReservedCash(),
                "第1条买入冻结必须撤销");
        assertEquals(new BigDecimal("100000"), e.getAccount("B").getCash());
        assertEquals(1000, e.getAccount("B").getPosition());
        assertEquals(10, e.getOrder("seedSell").getOpenQty(), "被吃单的挂单必须复原");
        assertEquals(OrderStatus.NEW, e.getOrder("seedSell").getStatus());
        assertEquals(10, e.getAccount("A").getReservedPosition());

        // 摘要维度也必须与提交前完全一致
        List<?> mismatches = Reconciler.diff(before, Reconciler.summarize(e));
        log("结果", "回滚后与提交前对账差异数=" + mismatches.size() + " " + mismatches);
        assertTrue(mismatches.isEmpty());

        // 回滚后同样的 seq 可重新提交并成功（水位已回退）
        CommandResult retry = e.apply(Command.placeLimit(1, "B", "BTC", "b1",
                Side.BUY, new BigDecimal("100"), 2));
        assertTrue(retry.accepted());
        assertEquals(2, retry.trades().get(0).quantity());
        log("结果", "seq=1 重提成功，说明整批确实没有落地");
    }

    @Test
    @DisplayName("整组没落地 vs 某一条有问题：BATCH_REJECTED 与成员拒绝原因明确区分")
    void batchLevelRejectionIsDistinguishableFromMemberRejection() {
        MatchingEngine e = engine();
        e.prime(10);

        // 1) 批次级非法：seq 不连续（10,12）
        BatchResult gap = e.applyAtomicBatch(List.of(
                Command.placeLimit(10, "A", "BTC", "g1",
                        Side.SELL, new BigDecimal("100"), 1),
                Command.placeLimit(12, "B", "BTC", "g2",
                        Side.BUY, new BigDecimal("100"), 1)));
        assertFalse(gap.committed());
        assertNotNull(gap.batchRejection());
        assertEquals(RejectReason.BATCH_REJECTED, gap.batchRejection().reason());
        assertNull(gap.memberRejection());
        log("判定", "seq 跳号 -> 批次级 " + gap.batchRejection().reason()
                + "：" + gap.batchRejection().detail());

        // 2) 批次级非法：与水位不衔接
        BatchResult notAligned = e.applyAtomicBatch(List.of(
                Command.placeLimit(20, "A", "BTC", "n1",
                        Side.SELL, new BigDecimal("100"), 1)));
        assertEquals(RejectReason.BATCH_REJECTED,
                notAligned.batchRejection().reason());
        log("判定", "起点不衔接水位 -> BATCH_REJECTED，水位仍=" + e.getLastSeq());
        assertEquals(9L, e.getLastSeq(), "批次级拒绝绝不推进水位");

        // 3) 批次级非法：组内 PLACE clientOrderId 重复
        BatchResult dup = e.applyAtomicBatch(List.of(
                Command.placeLimit(10, "A", "BTC", "dup",
                        Side.SELL, new BigDecimal("100"), 1),
                Command.placeLimit(11, "B", "BTC", "dup",
                        Side.BUY, new BigDecimal("100"), 1)));
        assertEquals(RejectReason.BATCH_REJECTED, dup.batchRejection().reason());
        log("判定", "组内 clientOrderId 重复 -> BATCH_REJECTED");

        // 4) 成员自身非法：数量为0 -> INVALID_QUANTITY，批次级原因为 null
        BatchResult member = e.applyAtomicBatch(List.of(
                Command.placeLimit(10, "A", "BTC", "ok",
                        Side.SELL, new BigDecimal("100"), 1),
                Command.placeLimit(11, "B", "BTC", "zero",
                        Side.BUY, new BigDecimal("100"), 0)));
        assertFalse(member.committed());
        assertNull(member.batchRejection());
        assertEquals(RejectReason.INVALID_QUANTITY, member.memberRejection().reason());
        log("判定", "成员数量非法 -> 成员原因 " + member.memberRejection().reason()
                + "，批次级原因=null，整组仍零生效");
        assertNull(e.getOrder("ok"));
    }

    @Test
    @DisplayName("并发下原子组不被其他批次穿插：两个组从不同线程提交，各自整体生效或整体回滚")
    void atomicBatchIsIndivisibleAcrossConcurrentBatches() throws Exception {
        MatchingEngine e = engine();
        e.registerAccount("D", new BigDecimal("100000"), 1000);
        // 盘前：A 卖 8@100
        e.apply(Command.placeLimit(0, "A", "BTC", "seed",
                Side.SELL, new BigDecimal("100"), 8));

        // 组从不同线程提交（Y 起点不连续，全序网关会缓冲等 X 落地后再整体应用 Y），
        // 两个原子组之间绝不互相穿插
        List<Command> groupX = List.of(
                Command.placeLimit(1, "B", "BTC", "x1",
                        Side.BUY, new BigDecimal("100"), 3),
                Command.placeLimit(2, "B", "BTC", "x2",
                        Side.BUY, new BigDecimal("90"), 1));
        // 组Y seq=3..4：D 挂买2@80（不成交、不依赖他组），再下一条数量0 的非法单 ->
        // 第2条为成员自身非法（INVALID_QUANTITY），整组回滚，第1条也不得残留
        List<Command> groupY = List.of(
                Command.placeLimit(3, "D", "BTC", "y1",
                        Side.BUY, new BigDecimal("80"), 2),
                Command.placeLimit(4, "D", "BTC", "y2",
                        Side.BUY, new BigDecimal("80"), 0));

        // 组从不同线程提交（Y 起点不连续，全序网关缓冲等 X 落地后再整体应用 Y），
        // 两个原子组之间绝不互相穿插
        var resultX = new java.util.concurrent.atomic.AtomicReference<BatchResult>();
        var resultY = new java.util.concurrent.atomic.AtomicReference<BatchResult>();
        Thread tx;
        Thread ty;
        try (var streaming = new com.github.h3yow.tradingenginejava.matching.gateway
                .StreamingGateway(e)) {
            streaming.prime(1); // 显式声明全序起点，到达顺序不影响锚定
            tx = new Thread(() -> resultX.set(
                    streaming.submitAtomicGroup(groupX).join()));
            ty = new Thread(() -> resultY.set(
                    streaming.submitAtomicGroup(groupY).join()));
            tx.start();
            ty.start();
            tx.join();
            ty.join();
        }

        assertTrue(resultX.get().committed(), "组X应整体成功");
        BatchResult ry = resultY.get();
        assertFalse(ry.committed(), "组Y第2条非法 -> 整组回滚");
        assertEquals(RejectReason.INVALID_QUANTITY, ry.memberRejection().reason());
        log("线程Y", "整组回滚，成员原因=" + ry.memberRejection().reason());

        // 无论两组的线程调度顺序如何：组Y 的 y1 绝不能残留（无冻结、无委托）
        assertNull(e.getOrder("y1"));
        assertEquals(0L, e.getAccount("D").getReservedCash().longValue());
        assertEquals(new BigDecimal("100000"), e.getAccount("D").getCash());
        // 组X 必然整体落地：seed 恰好被 x1 吃3 剩5，x2 挂簿
        assertEquals(5, e.getOrder("seed").getOpenQty());
        assertEquals(3, e.getTrades().stream().mapToLong(t -> t.quantity()).sum());
        assertNotNull(e.getOrder("x2"));
        assertEquals(2L, e.getLastSeq(), "回滚的组Y不推进水位，停在组X末尾");
        log("结果", "seed 余量=" + e.getOrder("seed").getOpenQty()
                + "，总成交量=" + e.getTrades().stream().mapToLong(t -> t.quantity()).sum()
                + "，y1 零残留，原子性在并发下成立，水位=2（回滚组不落地）");
    }
}
