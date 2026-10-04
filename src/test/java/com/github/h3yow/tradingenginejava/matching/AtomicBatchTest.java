package com.github.h3yow.tradingenginejava.matching;

import com.github.h3yow.tradingenginejava.matching.engine.MatchingEngine;
import com.github.h3yow.tradingenginejava.matching.model.Account;
import com.github.h3yow.tradingenginejava.matching.model.BatchResult;
import com.github.h3yow.tradingenginejava.matching.model.Command;
import com.github.h3yow.tradingenginejava.matching.model.CommandResult;
import com.github.h3yow.tradingenginejava.matching.model.OrderStatus;
import com.github.h3yow.tradingenginejava.matching.model.RejectReason;
import com.github.h3yow.tradingenginejava.matching.model.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 委托组原子提交单测：
 * 1) 整批成功：组内按序撮合，每条委托的结果可单独查询；
 * 2) 任意一条不合法 -> 整批回滚，对外零残留（无成交/无委托/无冻结变动），
 *    被回滚的 clientOrderId 可立即重用，后续 seq 连续推进；
 * 3) 整批被拒与单条被拒可区分：失败条保留自身原因，其余记 BATCH_ROLLED_BACK；
 * 4) 含回滚批的日志在另一引擎重放，拒绝序列与状态完全一致（确定性）。
 */
class AtomicBatchTest {

    private MatchingEngine newEngine() {
        MatchingEngine e = new MatchingEngine();
        e.registerAccount("A", new BigDecimal("100000"), 1000);
        e.registerAccount("B", new BigDecimal("100000"), 1000);
        e.registerAccount("C", new BigDecimal("300"), 10); // 小资金账户，用于制造失败
        return e;
    }

    private String balances(MatchingEngine e, String... ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            Account a = e.getAccount(id);
            sb.append(id).append("[cash=").append(a.getCash().toPlainString())
                    .append(",resvCash=").append(a.getReservedCash().toPlainString())
                    .append(",pos=").append(a.getPosition())
                    .append(",resvPos=").append(a.getReservedPosition()).append("] ");
        }
        return sb.toString();
    }

    @Test
    @DisplayName("整批成功：组内按序撮合（前单挂簿、后单成交），每条结果可单独查")
    void batchCommitAllOrNothingSuccess() {
        MatchingEngine e = newEngine();
        List<Command> batch = List.of(
                Command.placeLimit(1, "A", "BTC", "g-s1", Side.SELL, new BigDecimal("100"), 5),
                Command.placeLimit(2, "B", "BTC", "g-b1", Side.BUY, new BigDecimal("100"), 2),
                Command.placeLimit(3, "A", "ETH", "g-s2", Side.SELL, new BigDecimal("50"), 4));
        BatchResult r = e.applyBatch(batch);
        System.out.println("【输入】3 条一组原子提交（卖5@100、买2@100、卖4@50）");
        System.out.println("【判定】" + r);
        assertTrue(r.committed(), "三条均合法，整批应提交");
        assertEquals(3, r.size());

        // 每条委托各自的撮合结果可单独查询
        CommandResult sell = r.results().get(0);
        CommandResult buy = r.results().get(1);
        assertEquals(OrderStatus.NEW, sell.finalStatus());
        assertEquals(5, sell.remainingQty());
        assertEquals(OrderStatus.FILLED, buy.finalStatus());
        assertEquals(1, buy.trades().size(), "买2 应吃掉 g-s1 中的 2");
        assertEquals(2, buy.trades().get(0).quantity());
        System.out.println("【成交】g-b1 成交明细=" + buy.trades().get(0));

        // 引擎侧同样可逐条查到委托状态
        assertEquals(3, e.getOrder("g-s1").getOpenQty());
        assertEquals(OrderStatus.FILLED, e.getOrder("g-b1").getStatus());
        assertEquals(4, e.getOrder("g-s2").getOpenQty());
        assertEquals(3L, e.getLastSeq(), "整批 seq 1..3 全部消耗");
        System.out.println("【余额】" + balances(e, "A", "B"));
        System.out.println("【结果】整批生效，批内成交与逐条结果均可独立查询");
    }

    @Test
    @DisplayName("任意一条不合法 -> 整批回滚零残留，clientOrderId 可重用，seq 连续")
    void batchRollbackLeavesNoResidue() {
        MatchingEngine e = newEngine();
        String before = balances(e, "A", "B", "C");
        List<Command> batch = List.of(
                Command.placeLimit(1, "A", "BTC", "r-s1", Side.SELL, new BigDecimal("100"), 5),
                Command.placeLimit(2, "B", "BTC", "r-b1", Side.BUY, new BigDecimal("100"), 2),
                // C 只有 300 元现金，买 10@100 需 1000 -> INSUFFICIENT_CASH，整批回滚
                Command.placeLimit(3, "C", "BTC", "r-b2", Side.BUY, new BigDecimal("100"), 10));
        BatchResult r = e.applyBatch(batch);
        System.out.println("【输入】3 条一组，第 3 条 C 买 10@100 超出可用现金 300");
        System.out.println("【判定】" + r + " failure=" + r.failureReason());
        assertFalse(r.committed());
        assertEquals(RejectReason.INSUFFICIENT_CASH, r.failureReason());

        // 零残留：无成交、无委托、无冻结变动、无残留拒绝之外的状态
        assertTrue(e.getTrades().isEmpty(), "回滚后不应有任何成交");
        assertNull(e.getOrder("r-s1"), "回滚后批内委托不应存在");
        assertNull(e.getOrder("r-b1"));
        assertNull(e.getOrder("r-b2"));
        assertEquals(before, balances(e, "A", "B", "C"), "回滚后账户四分量必须逐位复原");
        System.out.println("【余额】回滚后=" + balances(e, "A", "B", "C"));

        // 拒绝记录可区分：前两条 BATCH_ROLLED_BACK，失败条自身原因
        assertEquals(3, e.getRejections().size());
        assertEquals(RejectReason.BATCH_ROLLED_BACK, e.getRejections().get(0).reason());
        assertEquals(RejectReason.BATCH_ROLLED_BACK, e.getRejections().get(1).reason());
        assertEquals(RejectReason.INSUFFICIENT_CASH, e.getRejections().get(2).reason());
        assertEquals(RejectReason.BATCH_ROLLED_BACK, r.results().get(0).rejection().reason());
        assertEquals(RejectReason.INSUFFICIENT_CASH, r.results().get(2).rejection().reason());
        System.out.println("【判定】拒绝序列=" + e.getRejections());

        // 被回滚的 clientOrderId 可立即重用，且 seq 从 4 继续（批 seq 已消耗）
        BatchResult retry = e.applyBatch(List.of(
                Command.placeLimit(4, "A", "BTC", "r-s1", Side.SELL, new BigDecimal("100"), 5),
                Command.placeLimit(5, "B", "BTC", "r-b1", Side.BUY, new BigDecimal("100"), 2)));
        System.out.println("【输入】修正后重提（seq 4..5，复用 r-s1/r-b1）=> 【判定】" + retry);
        assertTrue(retry.committed(), "修正后重提应成功（clientOrderId 无残留占用）");
        assertEquals(1, e.getTrades().size());
        assertEquals(5L, e.getLastSeq());
        System.out.println("【结果】回滚零残留、可重提、水位连续推进至 " + e.getLastSeq());
    }

    @Test
    @DisplayName("批内撤单/改单与下单混合：中间指令失败则撤改效果一并回滚")
    void batchWithCancelAndReplaceRollsBackTogether() {
        MatchingEngine e = newEngine();
        e.apply(Command.placeLimit(1, "A", "BTC", "x-s1", Side.SELL, new BigDecimal("100"), 5));
        e.apply(Command.placeLimit(2, "B", "BTC", "x-b1", Side.BUY, new BigDecimal("99"), 3));
        String before = balances(e, "A", "B");

        // 批内：撤掉 x-s1、改 x-b1 到 100、再引一条非法单（未知账户）-> 整批回滚
        BatchResult r = e.applyBatch(List.of(
                Command.cancel(3, "A", "x-s1"),
                Command.replace(4, "B", "x-b1", new BigDecimal("100"), 3),
                Command.placeLimit(5, "NOBODY", "BTC", "x-n1",
                        Side.BUY, new BigDecimal("100"), 1)));
        System.out.println("【输入】批内[撤x-s1, 改x-b1->100, 未知账户下单]");
        System.out.println("【判定】" + r + " failure=" + r.failureReason());
        assertFalse(r.committed());
        assertEquals(RejectReason.INVALID_COMMAND, r.failureReason());

        // 撤单与改单效果都必须回滚：x-s1 仍挂簿，x-b1 仍是 99
        assertEquals(5, e.getOrder("x-s1").getOpenQty());
        assertEquals(OrderStatus.NEW, e.getOrder("x-s1").getStatus());
        assertEquals(new BigDecimal("100"), e.getOrder("x-s1").getPrice());
        assertEquals(new BigDecimal("99"), e.getOrder("x-b1").getPrice());
        assertEquals(3, e.getOrder("x-b1").getOpenQty());
        assertEquals(before, balances(e, "A", "B"), "撤改回滚后冻结必须逐位复原");
        assertTrue(e.getTrades().isEmpty());
        System.out.println("【余额】回滚后=" + balances(e, "A", "B"));
        System.out.println("【结果】批内撤改一并回滚，旧单价格/冻结/挂簿完整保留");
    }

    @Test
    @DisplayName("重复提交同一批：逐条记 DUPLICATE_COMMAND，不触碰状态（幂等）")
    void duplicateBatchIsIdempotent() {
        MatchingEngine e = newEngine();
        List<Command> batch = List.of(
                Command.placeLimit(1, "A", "BTC", "d-s1", Side.SELL, new BigDecimal("100"), 5),
                Command.placeLimit(2, "B", "BTC", "d-b1", Side.BUY, new BigDecimal("100"), 2));
        BatchResult first = e.applyBatch(batch);
        assertTrue(first.committed());
        int trades = e.getTrades().size();
        String before = balances(e, "A", "B");

        BatchResult dup = e.applyBatch(batch);
        System.out.println("【输入】同一批（seq 1..2）重复提交 => 【判定】" + dup);
        assertFalse(dup.committed());
        assertEquals(RejectReason.DUPLICATE_COMMAND, dup.results().get(0).rejection().reason());
        assertEquals(RejectReason.DUPLICATE_COMMAND, dup.results().get(1).rejection().reason());
        assertEquals(trades, e.getTrades().size(), "重复批次不得产生重复成交");
        assertEquals(before, balances(e, "A", "B"));
        assertEquals(2L, e.getLastSeq(), "重复批不推进水位");
        System.out.println("【结果】重复批次幂等拒绝，状态零变化");
    }

    @Test
    @DisplayName("含回滚批的日志跨引擎重放：成交/拒绝/余额完全一致（确定性）")
    void rolledBackBatchReplaysDeterministically() {
        // 事件日志：普通单 + 一个会回滚的批（展开为连续 seq）+ 后续正常单
        List<Command> log = new ArrayList<>();
        log.add(Command.placeLimit(1, "A", "BTC", "p-s1", Side.SELL, new BigDecimal("100"), 5));
        // 批（seq 2..4）：第 3 条资金不足 -> 整批回滚
        log.add(Command.placeLimit(2, "A", "BTC", "p-s2", Side.SELL, new BigDecimal("99"), 3));
        log.add(Command.placeLimit(3, "B", "BTC", "p-b1", Side.BUY, new BigDecimal("99"), 1));
        log.add(Command.placeLimit(4, "C", "BTC", "p-b2", Side.BUY, new BigDecimal("100"), 10));
        log.add(Command.placeLimit(5, "B", "BTC", "p-b3", Side.BUY, new BigDecimal("100"), 2));

        MatchingEngine e1 = newEngine();
        e1.apply(log.get(0));
        BatchResult br = e1.applyBatch(log.subList(1, 4));
        e1.apply(log.get(4));
        System.out.println("【输入】日志=单条+回滚批(2..4)+单条，批判定=" + br);
        assertFalse(br.committed());
        String fp1 = fingerprint(e1);

        // 另一引擎重放完全相同的 applyBatch 序列：成交/拒绝/余额逐项一致
        MatchingEngine e3 = newEngine();
        e3.apply(log.get(0));
        e3.applyBatch(log.subList(1, 4));
        e3.apply(log.get(4));
        assertEquals(fp1, fingerprint(e3), "相同 applyBatch 序列必须完全可复现");
        System.out.println("【结果】原子批路径重放指纹一致=" + fp1.equals(fingerprint(e3)));
    }

    private String fingerprint(MatchingEngine e) {
        StringBuilder sb = new StringBuilder();
        e.getTrades().forEach(t -> sb.append(t.tradeId()).append('@')
                .append(t.price().toPlainString()).append('x').append(t.quantity()).append(';'));
        sb.append('|').append(balances(e, "A", "B", "C"));
        sb.append('|');
        e.getRejections().forEach(r -> sb.append(r.seq()).append(':').append(r.reason()).append(';'));
        return sb.toString();
    }
}
