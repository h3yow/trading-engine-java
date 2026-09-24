package com.github.h3yow.tradingenginejava;

import com.github.h3yow.tradingenginejava.command.Command;
import com.github.h3yow.tradingenginejava.command.CommandResult;
import com.github.h3yow.tradingenginejava.engine.EngineSnapshot;
import com.github.h3yow.tradingenginejava.engine.MatchingEngine;
import com.github.h3yow.tradingenginejava.model.Order;
import com.github.h3yow.tradingenginejava.model.OrderStatus;
import com.github.h3yow.tradingenginejava.model.OrderType;
import com.github.h3yow.tradingenginejava.model.RejectReason;
import com.github.h3yow.tradingenginejava.model.Side;
import com.github.h3yow.tradingenginejava.model.Trade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 撮合核心测试：价格/时间优先、部分成交、撤单改单、拒绝原因可区分、
 * 自成交拒绝、余额与订单状态守恒。
 */
class MatchingEngineTest {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngineTest.class);
    private static final String SYM = "BTC";

    private MatchingEngine seeded() {
        MatchingEngine e = new MatchingEngine();
        e.submitBatch(List.of(
                Command.deposit(1, "A", 1_000_000L, SYM, 0L),
                Command.deposit(2, "B", 1_000_000L, SYM, 1_000L),
                Command.deposit(3, "C", 1_000_000L, SYM, 1_000L)
        ));
        return e;
    }

    @Test
    @DisplayName("价格优先+时间优先：买单依次吃最低卖价、同价先挂单")
    void priceAndTimePriority() {
        MatchingEngine e = seeded();
        // 三笔卖单：100@110(B), 50@100(C), 80@100(B 同价先于 C? C 先)
        var r1 = e.submit(Command.place(4, "B", "s1", SYM, Side.SELL, OrderType.LIMIT, 110, 100));
        var r2 = e.submit(Command.place(5, "C", "s2", SYM, Side.SELL, OrderType.LIMIT, 100, 50));
        var r3 = e.submit(Command.place(6, "B", "s3", SYM, Side.SELL, OrderType.LIMIT, 100, 80));
        assertTrue(r1.accepted() && r2.accepted() && r3.accepted());

        log.info("[价格时间优先] 挂单: s1=100@110(B), s2=50@100(C seq5), s3=80@100(B seq6)");
        CommandResult buy = e.submit(Command.place(7, "A", "b1", SYM, Side.BUY, OrderType.LIMIT, 110, 100));
        log.info("[价格时间优先] 买单100@110 成交序号={}", java.util.Arrays.toString(buy.tradeSeqs()));

        List<Trade> trades = e.getTrades();
        // 买 100：先吃 100 价中时间最早的 s2 全量 50，再吃 s3 的 50（共 2 笔），s1@110 不触及
        assertEquals(2, trades.size());
        assertEquals("s2", trades.get(0).makerOrderId());
        assertEquals(100, trades.get(0).price());
        assertEquals(50, trades.get(0).qty());
        assertEquals("s3", trades.get(1).makerOrderId());
        assertEquals(100, trades.get(1).price());
        assertEquals(50, trades.get(1).qty());
        assertEquals(OrderStatus.PARTIALLY_FILLED, e.getOrder("s3").getStatus());
        assertEquals(50, e.getOrder("s3").getFilledQty());
        assertEquals(OrderStatus.FILLED, e.getOrder("b1").getStatus());
        assertEquals(100, e.getOrder("b1").getFilledQty());

        // 余额守恒：A 支出 100*100 = 10000，买入 100 BTC
        assertEquals(990_000L, e.snapshot().accounts().get(0).cash());
        assertEquals(100L, e.snapshot().accounts().get(0).positions().get(SYM));
        // s1 未成交：B 的 100 BTC 仍冻结
        Order s1 = e.getOrder("s1");
        assertEquals(OrderStatus.NEW, s1.getStatus());
    }

    @Test
    @DisplayName("部分成交：限价单只吃一部分，剩余挂簿且冻结与状态自洽")
    void partialFillResting() {
        MatchingEngine e = seeded();
        e.submit(Command.place(4, "B", "s1", SYM, Side.SELL, OrderType.LIMIT, 100, 60));
        CommandResult buy = e.submit(Command.place(5, "A", "b1", SYM, Side.BUY, OrderType.LIMIT, 100, 100));
        log.info("[部分成交] 买100 对 卖60 -> trades={}, basis={}",
                java.util.Arrays.toString(buy.tradeSeqs()), buy.message());

        Order b1 = e.getOrder("b1");
        assertEquals(OrderStatus.PARTIALLY_FILLED, b1.getStatus());
        assertEquals(40, b1.getRemainingQty());
        // 剩余 40 的买入现金仍冻结：100000 - 6000 成交 - 4000 冻结 = 990000
        var accA = e.snapshot().accounts().get(0);
        assertEquals(1_000_000L - 6_000L, accA.cash());
        assertEquals(4_000L, accA.reservedCash());

        // 再挂一个卖单吃完剩余
        e.submit(Command.place(6, "C", "s2", SYM, Side.SELL, OrderType.LIMIT, 90, 40));
        assertEquals(OrderStatus.FILLED, e.getOrder("b1").getStatus());
        assertEquals(0, e.snapshot().accounts().get(0).reservedCash());
    }

    @Test
    @DisplayName("撤单：未成交部分解冻，已成交部分保留；非活动单再撤被拒")
    void cancelReleasesReservation() {
        MatchingEngine e = seeded();
        e.submit(Command.place(4, "B", "s1", SYM, Side.SELL, OrderType.LIMIT, 100, 60));
        e.submit(Command.place(5, "A", "b1", SYM, Side.BUY, OrderType.LIMIT, 100, 100));
        // b1 剩余 40 挂簿；撤单
        CommandResult cancel = e.submit(Command.cancel(6, "A", "b1"));
        assertTrue(cancel.accepted());
        log.info("[撤单] {}", cancel.message());

        Order b1 = e.getOrder("b1");
        assertEquals(OrderStatus.CANCELLED, b1.getStatus());
        assertEquals(60, b1.getFilledQty());
        assertEquals(40, b1.getRemainingQty());
        var accA = e.snapshot().accounts().get(0);
        assertEquals(0, accA.reservedCash(), "撤单后剩余现金冻结必须清零");
        assertEquals(60L, accA.positions().get(SYM));

        CommandResult again = e.submit(Command.cancel(7, "A", "b1"));
        assertFalse(again.accepted());
        assertEquals(RejectReason.ORDER_NOT_ACTIVE, again.reason());
        log.info("[撤单] 重复撤单被拒 reason={} basis={}", again.reason(), again.message());
    }

    @Test
    @DisplayName("改单：提高数量重新校验限额，改单失去时间优先级；非法改单回滚")
    void modifyOrder() {
        MatchingEngine e = seeded();
        // B 卖 30@100，A 买 50@90 不成交，挂簿
        e.submit(Command.place(4, "B", "s1", SYM, Side.SELL, OrderType.LIMIT, 100, 30));
        e.submit(Command.place(5, "A", "b1", SYM, Side.BUY, OrderType.LIMIT, 90, 50));
        // 改价到 100、量 80：立即与 s1 成交 30，剩余 50 挂 100
        CommandResult mod = e.submit(Command.modify(6, "A", "b1", OrderType.LIMIT, 100, 80));
        assertTrue(mod.accepted(), mod.message());
        log.info("[改单] {}", mod.message());
        assertEquals(30, e.getOrder("b1").getFilledQty());
        assertEquals(50, e.getOrder("b1").getRemainingQty());
        assertEquals(100, e.getOrder("b1").getPrice());

        // 改成小于已成交量 => 拒绝且不改变状态
        CommandResult bad = e.submit(Command.modify(7, "A", "b1", OrderType.LIMIT, 100, 10));
        assertFalse(bad.accepted());
        assertEquals(RejectReason.INVALID_QUANTITY, bad.reason());
        assertEquals(80, e.getOrder("b1").getOriginalQty(), "非法改单必须回滚，原条款不变");
        log.info("[改单] 非法改单被拒 reason={} basis={}", bad.reason(), bad.message());
    }

    @Test
    @DisplayName("拒绝原因可区分：非法数量/非法价格/未知账户/未知委托/重复单号")
    void distinguishableRejections() {
        MatchingEngine e = seeded();
        assertReject(e, Command.place(4, "A", "b0", SYM, Side.BUY, OrderType.LIMIT, 100, 0),
                RejectReason.INVALID_QUANTITY);
        assertReject(e, Command.place(5, "A", "b0", SYM, Side.BUY, OrderType.LIMIT, -1, 10),
                RejectReason.INVALID_PRICE);
        assertReject(e, Command.place(6, "ZZ", "b0", SYM, Side.BUY, OrderType.MARKET, 0, 10),
                RejectReason.UNKNOWN_ACCOUNT);
        e.submit(Command.place(7, "A", "b1", SYM, Side.BUY, OrderType.LIMIT, 50, 10));
        assertReject(e, Command.place(8, "A", "b1", SYM, Side.BUY, OrderType.LIMIT, 50, 10),
                RejectReason.DUPLICATE_ORDER_ID);
        assertReject(e, Command.cancel(9, "A", "nope"), RejectReason.UNKNOWN_ORDER);
    }

    @Test
    @DisplayName("限额拒绝：买入现金不足、卖出持仓不足，必须明确拒绝而非丢弃")
    void limitChecksReject() {
        MatchingEngine e = new MatchingEngine();
        e.submit(Command.deposit(1, "A", 100L, SYM, 0L));
        e.submit(Command.deposit(2, "B", 0L, SYM, 5L));
        // 现金 100，买 10@100 需 1000
        CommandResult noCash = e.submit(Command.place(3, "A", "b1", SYM, Side.BUY, OrderType.LIMIT, 100, 10));
        assertEquals(RejectReason.INSUFFICIENT_CASH, noCash.reason());
        assertNull(e.getOrder("b1"), "被拒委托不得进入订单簿");
        log.info("[限额] 现金不足拒绝 reason={} basis={}", noCash.reason(), noCash.message());
        // 持仓 5，卖 10
        CommandResult noPos = e.submit(Command.place(4, "B", "s1", SYM, Side.SELL, OrderType.LIMIT, 10, 10));
        assertEquals(RejectReason.INSUFFICIENT_POSITION, noPos.reason());
        log.info("[限额] 持仓不足拒绝 reason={} basis={}", noPos.reason(), noPos.message());
        // 余额完全不变
        assertEquals(100L, e.snapshot().accounts().get(0).cash());
        assertEquals(0L, e.snapshot().accounts().get(0).reservedCash());
        assertEquals(5L, e.snapshot().accounts().get(1).positions().get(SYM));
    }

    @Test
    @DisplayName("自成交预检：会与自己对手单成交的委托整单拒绝，SELF_TRADE 可区分")
    void selfTradeRejected() {
        MatchingEngine e = seeded();
        e.submit(Command.deposit(4, "A", 0L, SYM, 100L));
        e.submit(Command.place(5, "A", "s1", SYM, Side.SELL, OrderType.LIMIT, 100, 50));
        // A 自己买 100@100 会命中自己的卖单
        CommandResult self = e.submit(Command.place(6, "A", "b1", SYM, Side.BUY, OrderType.LIMIT, 100, 50));
        assertFalse(self.accepted());
        assertEquals(RejectReason.SELF_TRADE, self.reason());
        log.info("[自成交] {} | basis={}", self.reason(), self.message());
        // 没有产生任何成交，卖单仍在簿
        assertEquals(0, e.getTrades().size());
        assertEquals(OrderStatus.NEW, e.getOrder("s1").getStatus());
        // 买入价格不交叉（99 < 100）则允许挂单
        CommandResult ok = e.submit(Command.place(7, "A", "b2", SYM, Side.BUY, OrderType.LIMIT, 99, 10));
        assertTrue(ok.accepted());
    }

    @Test
    @DisplayName("市价单：逐档成交，现金不足/无流动性时剩余撤销并带原因")
    void marketOrderBehavior() {
        MatchingEngine e = seeded();
        e.submit(Command.place(4, "B", "s1", SYM, Side.SELL, OrderType.LIMIT, 100, 5));
        e.submit(Command.place(5, "C", "s2", SYM, Side.SELL, OrderType.LIMIT, 101, 50));
        // A 只有 1_000_000，市价买 20：5@100 + 5@101(花505)，后续现金充足继续吃…
        // 这里构造现金受限场景：新账户 D 只有 800，市价买 20
        e.submit(Command.deposit(6, "D", 800L, SYM, 0L));
        CommandResult mkt = e.submit(Command.place(7, "D", "m1", SYM, Side.BUY, OrderType.MARKET, 0, 20));
        assertTrue(mkt.accepted());
        log.info("[市价] {}", mkt.message());
        Order m1 = e.getOrder("m1");
        // 5@100=500，剩 300；101 档只能买 2 (202)；总成交 7，剩余因现金不足撤销
        assertEquals(7, m1.getFilledQty());
        assertEquals(OrderStatus.CANCELLED, m1.getStatus());
        assertEquals(RejectReason.INSUFFICIENT_CASH, m1.getRejectReason());
        long cashLeft = 800L - 5 * 100L - 2 * 101L;
        assertEquals(cashLeft, e.snapshot().accounts().get(3).cash());
        assertEquals(0, e.snapshot().accounts().get(3).reservedCash());

        // 无流动性市价单直接拒绝
        MatchingEngine e2 = seeded();
        CommandResult noLiq = e2.submit(Command.place(4, "A", "m2", SYM, Side.BUY, OrderType.MARKET, 0, 10));
        assertEquals(RejectReason.NO_LIQUIDITY, noLiq.reason());
        log.info("[市价] 无流动性拒绝 basis={}", noLiq.message());
    }

    @Test
    @DisplayName("全局守恒：现金与证券总量在多笔成交/撤单后保持不变")
    void conservation() {
        MatchingEngine e = seeded();
        List<Command> cmds = List.of(
                Command.place(4, "B", "s1", SYM, Side.SELL, OrderType.LIMIT, 100, 100),
                Command.place(5, "C", "s2", SYM, Side.SELL, OrderType.LIMIT, 99, 30),
                Command.place(6, "A", "b1", SYM, Side.BUY, OrderType.LIMIT, 100, 120),
                Command.cancel(7, "B", "s1"),
                Command.place(8, "B", "s3", SYM, Side.SELL, OrderType.LIMIT, 100, 100),
                Command.place(9, "A", "b2", SYM, Side.BUY, OrderType.MARKET, 0, 50));
        for (Command c : cmds) {
            CommandResult r = e.submit(c);
            log.info("[守恒] seq={} accepted={} {}", c.seq(), r.accepted(), r.message());
        }
        EngineSnapshot snap = e.snapshot();
        long totalCash = snap.accounts().stream().mapToLong(EngineSnapshot.AccountSnapshot::cash).sum();
        long totalReservedCash = snap.accounts().stream().mapToLong(EngineSnapshot.AccountSnapshot::reservedCash).sum();
        long totalQty = snap.accounts().stream()
                .mapToLong(a -> a.positions().getOrDefault(SYM, 0L)).sum();
        long totalReservedQty = snap.accounts().stream()
                .mapToLong(a -> a.reservedPositions().getOrDefault(SYM, 0L)).sum();
        log.info("[守恒] 现金总额={}(+冻结{}) 持仓总量={}(+冻结{})",
                totalCash, totalReservedCash, totalQty, totalReservedQty);
        assertEquals(3_000_000L, totalCash, "现金仅在账户间转移，总额守恒");
        assertEquals(2_000L, totalQty, "证券仅在账户间转移，总量守恒");
        assertTrue(totalReservedCash >= 0);
        assertTrue(totalReservedQty >= 0);
        // 不允许负余额
        snap.accounts().forEach(a -> {
            assertTrue(a.cash() >= 0 && a.reservedCash() >= 0);
            a.positions().values().forEach(v -> assertTrue(v >= 0));
        });
        // 无重复成交号
        long distinct = e.getTrades().stream().mapToLong(Trade::tradeSeq).distinct().count();
        assertEquals(e.getTrades().size(), distinct);
    }

    private void assertReject(MatchingEngine e, Command c, RejectReason reason) {
        CommandResult r = e.submit(c);
        assertFalse(r.accepted(), "expected reject " + reason + " but accepted");
        assertEquals(reason, r.reason(), "basis=" + r.message());
        log.info("[拒绝] seq={} expected={} basis={}", c.seq(), reason, r.message());
    }
}
