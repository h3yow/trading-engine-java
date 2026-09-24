package com.github.h3yow.tradingenginejava.matching;

import com.github.h3yow.tradingenginejava.matching.engine.MatchingEngine;
import com.github.h3yow.tradingenginejava.matching.model.Command;
import com.github.h3yow.tradingenginejava.matching.model.CommandResult;
import com.github.h3yow.tradingenginejava.matching.model.Order;
import com.github.h3yow.tradingenginejava.matching.model.OrderStatus;
import com.github.h3yow.tradingenginejava.matching.model.RejectReason;
import com.github.h3yow.tradingenginejava.matching.model.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 改单边界单测：价格改善后即时成交的冻结结算正确性、改单资金不足时旧单完整保留、
 * 改单自成交回滚、以及成交/冻结/余额的守恒。
 */
class ReplaceEdgeCaseTest {

    private long seq = 0;

    private long n() {
        return ++seq;
    }

    private MatchingEngine engine() {
        MatchingEngine e = new MatchingEngine();
        e.registerAccount("A", new BigDecimal("100000"), 1000);
        e.registerAccount("B", new BigDecimal("100000"), 1000);
        e.registerAccount("C", new BigDecimal("500"), 0);
        return e;
    }

    private CommandResult apply(MatchingEngine e, Command c) {
        System.out.println("【输入】" + c);
        CommandResult r = e.apply(c);
        System.out.println("【判定】" + r);
        return r;
    }

    @Test
    @DisplayName("买单改价到可成交：从冻结现金即时结算，余量冻结正确，无超扣")
    void replaceBuyToCrossingSettlesFromReservation() {
        MatchingEngine e = engine();
        // A 卖 5@100
        apply(e, Command.placeLimit(n(), "A", "BTC", "s1",
                Side.SELL, new BigDecimal("100"), 5));
        // B 挂买 5@90（不成交，冻结 450）
        apply(e, Command.placeLimit(n(), "B", "BTC", "b1",
                Side.BUY, new BigDecimal("90"), 5));
        assertEquals(new BigDecimal("450"), e.getAccount("B").getReservedCash());

        // B 改单：价 100、总量 5 -> 全额冻结 500，立即吃 s1 成交5
        CommandResult rep = apply(e, Command.replace(n(), "B", "b1",
                new BigDecimal("100"), 5));
        assertTrue(rep.accepted(), () -> "改单应成功: " + rep);
        assertEquals(OrderStatus.FILLED, rep.finalStatus());
        assertEquals(1, rep.trades().size());
        assertEquals(5, rep.trades().get(0).quantity());
        assertEquals(new BigDecimal("100"), rep.trades().get(0).price());

        // 买方：现金 100000-500=99500，无残留冻结；持仓 +5
        assertEquals(new BigDecimal("99500"), e.getAccount("B").getCash());
        assertEquals(BigDecimal.ZERO, e.getAccount("B").getReservedCash());
        assertEquals(1005, e.getAccount("B").getPosition());
        // 卖方：收 500，交割 5
        assertEquals(new BigDecimal("100500"), e.getAccount("A").getCash());
        assertEquals(995, e.getAccount("A").getPosition());
        assertEquals(0, e.getAccount("A").getReservedPosition());
    }

    @Test
    @DisplayName("卖单改量超过可用持仓：整单拒绝，旧单价格/数量/冻结保持不变")
    void replaceSellBeyondPositionLeavesOldOrderIntact() {
        MatchingEngine e = engine(); // C 持仓 0
        // 先让 C 买入 2 股（市价，200 元@100）
        apply(e, Command.placeLimit(n(), "A", "BTC", "s1",
                Side.SELL, new BigDecimal("100"), 10));
        apply(e, Command.placeMarket(n(), "C", "BTC", "c-mkt",
                Side.BUY, 2));
        // C 挂卖 2@120（冻结全部 2 股）
        apply(e, Command.placeLimit(n(), "C", "BTC", "c-sell",
                Side.SELL, new BigDecimal("120"), 2));
        assertEquals(2, e.getAccount("C").getReservedPosition());

        // 改单到总量 3（已成交0，余量3 > 可用0+旧冻2）-> INSUFFICIENT_POSITION，旧单不动
        CommandResult rep = apply(e, Command.replace(n(), "C", "c-sell",
                new BigDecimal("121"), 3));
        assertFalse(rep.accepted());
        assertEquals(RejectReason.INSUFFICIENT_POSITION, rep.rejection().reason());

        Order old = e.getOrder("c-sell");
        assertEquals(new BigDecimal("120"), old.getPrice());
        assertEquals(2, old.getTotalQty());
        assertEquals(OrderStatus.NEW, old.getStatus());
        assertEquals(2, e.getAccount("C").getReservedPosition(), "旧冻结必须原样保留");
        // 旧单仍可成交：B 买2@120 吃掉
        CommandResult buy = apply(e, Command.placeLimit(n(), "B", "BTC", "b-hit",
                Side.BUY, new BigDecimal("120"), 2));
        assertEquals(OrderStatus.FILLED, buy.finalStatus());
        assertEquals(2, buy.trades().get(0).quantity());
    }

    @Test
    @DisplayName("改单后新价造成自成交：整单 SELF_TRADE 拒绝并回滚，旧单重新挂回")
    void replaceIntoSelfTradeRollsBack() {
        MatchingEngine e = engine();
        // A 卖 5@100
        apply(e, Command.placeLimit(n(), "A", "BTC", "s1",
                Side.SELL, new BigDecimal("100"), 5));
        // A 自己再挂买 5@90（不与自己的卖盘交叉）
        apply(e, Command.placeLimit(n(), "A", "BTC", "b1",
                Side.BUY, new BigDecimal("90"), 5));
        assertEquals(new BigDecimal("450"), e.getAccount("A").getReservedCash());
        assertEquals(5, e.getAccount("A").getReservedPosition());

        // 把买单改价到 100，会与自己 s1 成交 -> SELF_TRADE，旧买单回滚
        CommandResult rep = apply(e, Command.replace(n(), "A", "b1",
                new BigDecimal("100"), 5));
        assertFalse(rep.accepted());
        assertEquals(RejectReason.SELF_TRADE, rep.rejection().reason());

        Order old = e.getOrder("b1");
        assertEquals(new BigDecimal("90"), old.getPrice(), "回滚后旧价恢复");
        assertEquals(OrderStatus.NEW, old.getStatus());
        assertEquals(new BigDecimal("450"), e.getAccount("A").getReservedCash());
        assertEquals(5, e.getAccount("A").getReservedPosition());
        // 没有产生任何成交
        assertEquals(0, e.getTrades().size());
    }
}
