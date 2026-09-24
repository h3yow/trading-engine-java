package com.github.h3yow.tradingenginejava.matching;

import com.github.h3yow.tradingenginejava.matching.engine.MatchingEngine;
import com.github.h3yow.tradingenginejava.matching.engine.RiskConfig;
import com.github.h3yow.tradingenginejava.matching.model.Account;
import com.github.h3yow.tradingenginejava.matching.model.Command;
import com.github.h3yow.tradingenginejava.matching.model.CommandResult;
import com.github.h3yow.tradingenginejava.matching.model.Order;
import com.github.h3yow.tradingenginejava.matching.model.OrderStatus;
import com.github.h3yow.tradingenginejava.matching.model.RejectReason;
import com.github.h3yow.tradingenginejava.matching.model.Side;
import com.github.h3yow.tradingenginejava.matching.model.Trade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 撮合规则单测：部分成交、撤单、改单、拒绝原因可区分、余额/状态守恒。
 * 每个用例都先打印【输入】与【判定依据】，再打印【结果】。
 */
class MatchingEngineTest {

    private static final String SYM = "BTC";
    private long seq = 0;

    private long nextSeq() {
        return ++seq;
    }

    private MatchingEngine newEngine() {
        MatchingEngine e = new MatchingEngine(new RiskConfig(3, new BigDecimal("100000")));
        e.registerAccount("A", new BigDecimal("100000"), 1000);
        e.registerAccount("B", new BigDecimal("100000"), 1000);
        e.registerAccount("C", new BigDecimal("500"), 20);
        return e;
    }

    private CommandResult apply(MatchingEngine e, Command c) {
        System.out.println("【输入】" + c);
        CommandResult r = e.apply(c);
        System.out.println("【判定】" + r);
        r.trades().forEach(t -> System.out.println("        成交: "
                + t.makerClientOrderId() + "x" + t.takerClientOrderId()
                + " " + t.quantity() + "@" + t.price()
                + " 买" + t.buyerAccountId() + " 卖" + t.sellerAccountId()));
        return r;
    }

    private void printAccounts(MatchingEngine e) {
        System.out.println("【余额】" + e.getAccount("A"));
        System.out.println("【余额】" + e.getAccount("B"));
        System.out.println("【余额】" + e.getAccount("C"));
    }

    @Test
    @DisplayName("部分成交：卖5被买3吃掉后剩2挂簿，状态与冻结自洽")
    void partialFillLeavesRestingRemainder() {
        MatchingEngine e = newEngine();
        CommandResult sell = apply(e, Command.placeLimit(nextSeq(), "A", SYM, "s1",
                Side.SELL, new BigDecimal("100"), 5));
        CommandResult buy = apply(e, Command.placeLimit(nextSeq(), "B", SYM, "b1",
                Side.BUY, new BigDecimal("100"), 3));

        assertEquals(OrderStatus.NEW, sell.finalStatus());
        assertEquals(OrderStatus.FILLED, buy.finalStatus());
        assertEquals(1, e.getTrades().size());
        Trade t = e.getTrades().get(0);
        assertEquals(3, t.quantity());
        assertEquals(new BigDecimal("100"), t.price()); // 成交价=挂单价

        Order s1 = orderOf(e, "s1");
        assertEquals(5, s1.getTotalQty());
        assertEquals(3, s1.getFilledQty());
        assertEquals(2, s1.getOpenQty());
        assertTrue(s1.isLive());

        Account a = e.getAccount("A");
        assertEquals(2, a.getReservedPosition()); // 剩余2继续冻结
        assertEquals(997, a.getPosition());       // 交割3
        assertEquals(new BigDecimal("100300"), a.getCash());
        Account b = e.getAccount("B");
        assertEquals(1003, b.getPosition());
        assertEquals(new BigDecimal("99700"), b.getCash());
        assertEquals(BigDecimal.ZERO, b.getReservedCash());
        printAccounts(e);
    }

    @Test
    @DisplayName("价格优先：买单应先与最低卖价成交，时间优先体现在同价 FIFO")
    void priceTimePriority() {
        MatchingEngine e = newEngine();
        apply(e, Command.placeLimit(nextSeq(), "A", SYM, "s1",
                Side.SELL, new BigDecimal("102"), 2));
        apply(e, Command.placeLimit(nextSeq(), "A", SYM, "s2",
                Side.SELL, new BigDecimal("100"), 2));
        apply(e, Command.placeLimit(nextSeq(), "C", SYM, "s3",
                Side.SELL, new BigDecimal("100"), 2));
        CommandResult buy = apply(e, Command.placeLimit(nextSeq(), "B", SYM, "b1",
                Side.BUY, new BigDecimal("105"), 3));

        // b1 先吃 s2(100,2) 再吃 s3(100,1)，102 的 s1 不动
        assertEquals(2, buy.trades().size());
        assertEquals("s2", buy.trades().get(0).makerClientOrderId());
        assertEquals(new BigDecimal("100"), buy.trades().get(0).price());
        assertEquals("s3", buy.trades().get(1).makerClientOrderId());
        assertEquals(2, orderOf(e, "s1").getOpenQty());
        assertEquals(1, orderOf(e, "s3").getOpenQty());
    }

    @Test
    @DisplayName("撤单：撤剩余量后冻结释放，重复撤单/撤未知委托按 UNKNOWN_ORDER 拒绝")
    void cancelReleasesReservationAndUnknownIsRejected() {
        MatchingEngine e = newEngine();
        apply(e, Command.placeLimit(nextSeq(), "A", SYM, "s1",
                Side.SELL, new BigDecimal("100"), 5));
        apply(e, Command.placeLimit(nextSeq(), "B", SYM, "b1",
                Side.BUY, new BigDecimal("100"), 3));
        // s1 已成交3，剩余2冻结
        assertEquals(2, e.getAccount("A").getReservedPosition());

        CommandResult cancel = apply(e, Command.cancel(nextSeq(), "A", "s1"));
        assertTrue(cancel.accepted());
        assertEquals(OrderStatus.CANCELED, orderOf(e, "s1").getStatus());
        assertEquals(0, e.getAccount("A").getReservedPosition());

        CommandResult again = apply(e, Command.cancel(nextSeq(), "A", "s1"));
        assertFalse(again.accepted());
        assertEquals(RejectReason.UNKNOWN_ORDER, again.rejection().reason());

        CommandResult ghost = apply(e, Command.cancel(nextSeq(), "A", "nope"));
        assertEquals(RejectReason.UNKNOWN_ORDER, ghost.rejection().reason());
    }

    @Test
    @DisplayName("改单：已成交部分保留，新余量重新挂簿并丢失时间优先；非法改单可区分拒绝")
    void replaceKeepsFilledQuantityAndRequeues() {
        MatchingEngine e = newEngine();
        apply(e, Command.placeLimit(nextSeq(), "A", SYM, "s1",
                Side.SELL, new BigDecimal("100"), 5));
        apply(e, Command.placeLimit(nextSeq(), "B", SYM, "b1",
                Side.BUY, new BigDecimal("100"), 2)); // s1 成交2，剩3
        apply(e, Command.placeLimit(nextSeq(), "B", SYM, "s2",
                Side.SELL, new BigDecimal("99"), 3));  // 同价位时间优先占位（更低价）
        // s1 改价 99、新总量4（已成交2 -> 新余量2）
        CommandResult rep = apply(e, Command.replace(nextSeq(), "A", "s1",
                new BigDecimal("99"), 4));
        assertTrue(rep.accepted(), () -> "改单应成功: " + rep);
        Order replaced = orderOf(e, "s1");
        assertEquals(4, replaced.getTotalQty());
        assertEquals(2, replaced.getFilledQty());
        assertEquals(2, replaced.getOpenQty());
        assertEquals(2, e.getAccount("A").getReservedPosition()); // 新余量冻结

        // 改单后 s1 排在 s2 之后：买方4个先吃 s2(3) 再吃 s1(1)
        CommandResult buy = apply(e, Command.placeLimit(nextSeq(), "C", SYM, "b2",
                Side.BUY, new BigDecimal("99"), 4));
        assertEquals(2, buy.trades().size());
        assertEquals("s2", buy.trades().get(0).makerClientOrderId());
        assertEquals("s1", buy.trades().get(1).makerClientOrderId());
        assertEquals(1, buy.trades().get(1).quantity());

        // 非法改单：新总量 <= 已成交量
        Order s1 = orderOf(e, "s1");
        CommandResult bad = apply(e, Command.replace(nextSeq(), "A", "s1",
                new BigDecimal("98"), s1.getFilledQty()));
        assertEquals(RejectReason.INVALID_QUANTITY, bad.rejection().reason());
    }

    @Test
    @DisplayName("拒绝原因区分：非法量/非法价/自成交/资金不足/持仓不足/重复指令")
    void rejectionReasonsAreDistinguishable() {
        MatchingEngine e = newEngine();

        assertReject(e, Command.placeLimit(nextSeq(), "A", SYM, "q0",
                Side.BUY, new BigDecimal("100"), 0), RejectReason.INVALID_QUANTITY);
        assertReject(e, Command.placeLimit(nextSeq(), "A", SYM, "qneg",
                Side.SELL, new BigDecimal("100"), -3), RejectReason.INVALID_QUANTITY);
        assertReject(e, Command.placeLimit(nextSeq(), "A", SYM, "p0",
                Side.BUY, BigDecimal.ZERO, 1), RejectReason.INVALID_PRICE);

        // 自成交：A 先挂卖，再以可及价格买 -> SELF_TRADE
        apply(e, Command.placeLimit(nextSeq(), "A", SYM, "selfSell",
                Side.SELL, new BigDecimal("100"), 2));
        assertReject(e, Command.placeLimit(nextSeq(), "A", SYM, "selfBuy",
                Side.BUY, new BigDecimal("100"), 2), RejectReason.SELF_TRADE);

        // 资金不足：C 现金500，买10@100 需要1000
        assertReject(e, Command.placeLimit(nextSeq(), "C", SYM, "poorBuy",
                Side.BUY, new BigDecimal("100"), 10), RejectReason.INSUFFICIENT_CASH);
        // 持仓不足：C 持仓20，先冻结20，再卖1 -> 不足
        apply(e, Command.placeLimit(nextSeq(), "C", SYM, "freezeAll",
                Side.SELL, new BigDecimal("200"), 20));
        assertReject(e, Command.placeLimit(nextSeq(), "C", SYM, "shortSell",
                Side.SELL, new BigDecimal("200"), 1), RejectReason.INSUFFICIENT_POSITION);

        // 重复 clientOrderId 与重复 seq
        assertReject(e, Command.placeLimit(nextSeq(), "A", SYM, "selfSell",
                Side.SELL, new BigDecimal("100"), 2), RejectReason.DUPLICATE_COMMAND);
        long dupSeq = nextSeq();
        apply(e, Command.cancel(dupSeq, "A", "freezeAll"));
        CommandResult dup = e.apply(Command.cancel(dupSeq, "A", "freezeAll"));
        assertEquals(RejectReason.DUPLICATE_COMMAND, dup.rejection().reason());
    }

    @Test
    @DisplayName("风控限额：在途委托数上限触发 LIMIT_EXCEEDED，明确拒绝而非丢弃")
    void riskLimitExceededIsRejected() {
        MatchingEngine e = newEngine(); // 每账户上限3
        apply(e, Command.placeLimit(nextSeq(), "A", SYM, "o1",
                Side.SELL, new BigDecimal("100"), 1));
        apply(e, Command.placeLimit(nextSeq(), "A", SYM, "o2",
                Side.SELL, new BigDecimal("101"), 1));
        apply(e, Command.placeLimit(nextSeq(), "A", SYM, "o3",
                Side.SELL, new BigDecimal("102"), 1));
        CommandResult over = apply(e, Command.placeLimit(nextSeq(), "A", SYM, "o4",
                Side.SELL, new BigDecimal("103"), 1));
        assertFalse(over.accepted());
        assertEquals(RejectReason.LIMIT_EXCEEDED, over.rejection().reason());

        // 名义金额上限：1000 股@100 = 100000 恰好，1001 超限
        CommandResult notional = apply(e, Command.placeLimit(nextSeq(), "B", SYM, "big",
                Side.BUY, new BigDecimal("100"), 1001));
        assertEquals(RejectReason.LIMIT_EXCEEDED, notional.rejection().reason());
    }

    @Test
    @DisplayName("市价单 IOC：吃光可及流动性后余量撤销；无流动性按 MARKET_NO_LIQUIDITY 拒绝")
    void marketOrderIocAndNoLiquidity() {
        MatchingEngine e = newEngine();
        apply(e, Command.placeLimit(nextSeq(), "A", SYM, "s1",
                Side.SELL, new BigDecimal("100"), 2));
        CommandResult mkt = apply(e, Command.placeMarket(nextSeq(), "B", SYM, "m1",
                Side.BUY, 5));
        assertTrue(mkt.accepted());
        assertEquals(OrderStatus.CANCELED, mkt.finalStatus()); // 余量 IOC 撤销
        assertEquals(0, mkt.remainingQty());
        assertEquals(1, mkt.trades().size());
        assertEquals(2, mkt.trades().get(0).quantity());
        // 未成交部分不冻结任何现金
        assertEquals(BigDecimal.ZERO, e.getAccount("B").getReservedCash());

        CommandResult noLiq = apply(e, Command.placeMarket(nextSeq(), "B", SYM, "m2",
                Side.BUY, 1));
        assertEquals(RejectReason.MARKET_NO_LIQUIDITY, noLiq.rejection().reason());
    }

    @Test
    @DisplayName("市价买入遇到现金不足：在当前价位可承受范围内成交，绝不出现负余额")
    void marketBuyStopsWhenCashRunsOut() {
        MatchingEngine e = newEngine(); // C 现金500
        apply(e, Command.placeLimit(nextSeq(), "A", SYM, "s1",
                Side.SELL, new BigDecimal("100"), 10));
        CommandResult mkt = apply(e, Command.placeMarket(nextSeq(), "C", SYM, "m1",
                Side.BUY, 10));
        // 500 元只能买 5 股，剩余市价量撤销
        assertEquals(5, mkt.trades().get(0).quantity());
        assertEquals(OrderStatus.CANCELED, mkt.finalStatus());
        Account c = e.getAccount("C");
        assertEquals(BigDecimal.ZERO, c.getAvailableCash());
        assertEquals(25, c.getPosition());
        assertEquals(0, c.getReservedPosition());
        printAccounts(e);
    }

    private Order orderOf(MatchingEngine e, String coid) {
        Order o = e.getOrder(coid);
        assertNotNull(o, "引擎中找不到委托 " + coid);
        assertEquals(o.getOpenQty(), o.getTotalQty() - o.getFilledQty() - o.getCanceledQty(),
                "openQty 必须自洽: " + coid);
        return o;
    }

    private void assertReject(MatchingEngine e, Command c, RejectReason reason) {
        CommandResult r = apply(e, c);
        assertFalse(r.accepted(), () -> "本应拒绝(" + reason + ")却接受: " + c);
        assertEquals(reason, r.rejection().reason(),
                () -> "拒绝原因不符: " + r.rejection());
    }
}
