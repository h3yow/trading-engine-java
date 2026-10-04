package com.github.h3yow.tradingenginejava.matching;

import com.github.h3yow.tradingenginejava.matching.engine.MatchingEngine;
import com.github.h3yow.tradingenginejava.matching.model.Command;
import com.github.h3yow.tradingenginejava.matching.model.Side;
import com.github.h3yow.tradingenginejava.matching.reconcile.Mismatch;
import com.github.h3yow.tradingenginejava.matching.reconcile.ReconciliationSummary;
import com.github.h3yow.tradingenginejava.matching.reconcile.Reconciler;
import com.github.h3yow.tradingenginejava.matching.snapshot.Snapshot;
import com.github.h3yow.tradingenginejava.matching.util.JsonIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 盘后对账摘要单测。
 *
 * <p>覆盖：同一状态两次摘要逐字节相同（可复现）；状态一致时摘要一致、差异为空；
 * 状态不一致时差异能定位到具体账户/标的与维度（现金、持仓、订单数、成交笔数、成交量）。
 */
class ReconcilerTest {

    private MatchingEngine scenarioA() {
        MatchingEngine e = new MatchingEngine();
        e.registerAccount("A", new BigDecimal("100000"), 1000);
        e.registerAccount("B", new BigDecimal("100000"), 1000);
        e.registerAccount("C", new BigDecimal("100000"), 1000);
        long s = 0;
        e.apply(Command.placeLimit(++s, "A", "BTC", "aSell",
                Side.SELL, new BigDecimal("100"), 10));
        e.apply(Command.placeLimit(++s, "B", "BTC", "bBuy",
                Side.BUY, new BigDecimal("100"), 4));   // 成交4
        e.apply(Command.placeLimit(++s, "C", "ETH", "cSell",
                Side.SELL, new BigDecimal("50"), 6));
        e.apply(Command.placeLimit(++s, "B", "ETH", "bBuy2",
                Side.BUY, new BigDecimal("50"), 2));    // 成交2
        e.apply(Command.cancel(++s, "C", "cSell"));      // 余4撤
        // 一条必然被拒的委托（资金不足），不应计入订单数
        e.apply(Command.placeLimit(++s, "C", "BTC", "poor",
                Side.BUY, new BigDecimal("100"), 10_000));
        return e;
    }

    @Test
    @DisplayName("摘要口径正确：订单数（不含被拒）/成交笔数/成交量/现金/按标的净持仓")
    void summaryDimensionsAreCorrect() {
        MatchingEngine e = scenarioA();
        ReconciliationSummary sum = Reconciler.summarize(e);
        System.out.println("【输入】2 标的、3 账户：BTC 成交4、ETH 成交2 后卖方余4撤销、1 单被拒");
        System.out.println("【摘要】" + JsonIO.toJson(sum));

        // A：卖出 BTC 4 -> 现金 100400，持仓 996（BTC 净持仓 -4）
        var a = sum.accounts().get("A");
        assertEquals(1, a.orderCount());
        assertEquals(1, a.tradeCount());
        assertEquals(4, a.filledQuantity());
        assertEquals("100400", a.cash());
        assertEquals(-4L, a.positions().get("BTC"));

        // B：买入 BTC4 + ETH2 -> 现金 100000-400-100=99500，持仓 +6
        var b = sum.accounts().get("B");
        assertEquals(2, b.orderCount());
        assertEquals(2, b.tradeCount());
        assertEquals(6, b.filledQuantity());
        assertEquals("99500", b.cash());
        assertEquals(4L, b.positions().get("BTC"));
        assertEquals(2L, b.positions().get("ETH"));

        // C：cSell 被撤（算 1 单落地）、poor 被拒（不算）；卖 ETH2 -> 现金100100
        var c = sum.accounts().get("C");
        assertEquals(1, c.orderCount(), "被拒委托不计入订单数");
        assertEquals(1, c.tradeCount());
        assertEquals(2, c.filledQuantity());
        assertEquals("100100", c.cash());
        assertEquals(-2L, c.positions().get("ETH"));

        // 标的维度（双边：买卖各计）
        var btc = sum.symbols().get("BTC");
        assertEquals(1, btc.tradeCount());
        assertEquals(4, btc.filledQuantity());
        var eth = sum.symbols().get("ETH");
        assertEquals(1, eth.tradeCount());
        assertEquals(2, eth.filledQuantity());
        System.out.println("【判定】各账户订单数/笔数/量/现金/持仓与标的双边统计逐项正确");
    }

    @Test
    @DisplayName("同一状态两次导出的摘要逐字节相同（确定性、可复现）")
    void summaryIsByteReproducible() {
        MatchingEngine e = scenarioA();
        String j1 = JsonIO.toJson(Reconciler.summarize(e));
        String j2 = JsonIO.toJson(Reconciler.summarize(e));
        assertEquals(j1, j2, "同状态摘要必须逐字节一致");

        // 经快照恢复到另一个实例，摘要也必须一致
        MatchingEngine rebuilt = new MatchingEngine();
        rebuilt.restore(JsonIO.fromJson(JsonIO.toJson(e.snapshot()), Snapshot.class));
        String j3 = JsonIO.toJson(Reconciler.summarize(rebuilt));
        assertEquals(j1, j3, "恢复实例摘要与原实例不一致");
        System.out.println("【判定】同状态两次摘要 + 快照恢复后摘要，JSON 逐字节一致");
    }

    @Test
    @DisplayName("状态一致：差异列表为空（摘要一致即状态一致）")
    void equalStatesHaveNoMismatches() {
        MatchingEngine e1 = scenarioA();
        MatchingEngine e2 = new MatchingEngine();
        e2.restore(JsonIO.fromJson(JsonIO.toJson(e1.snapshot()), Snapshot.class));
        List<Mismatch> mm = Reconciler.compare(e1, e2);
        System.out.println("【判定】一致状态差异数=" + mm.size());
        assertTrue(mm.isEmpty(), () -> "不应有差异: " + mm);
    }

    @Test
    @DisplayName("状态不一致：差异精确定位到账户/标的与维度（现金、持仓、成交）")
    void differentStatesPinpointAccountSymbolAndDimension() {
        MatchingEngine base = scenarioA();

        // 漂移实例：在基准之上多做一笔成交（B 再买 BTC3，吃 A 的剩余卖单）
        MatchingEngine drifted = new MatchingEngine();
        drifted.restore(JsonIO.fromJson(JsonIO.toJson(base.snapshot()), Snapshot.class));
        drifted.apply(Command.placeLimit(base.getLastSeq() + 1, "B", "BTC", "extra",
                Side.BUY, new BigDecimal("100"), 3));

        ReconciliationSummary sBase = Reconciler.summarize(base);
        ReconciliationSummary sDrift = Reconciler.summarize(drifted);
        List<Mismatch> mm = Reconciler.diff(sBase, sDrift);
        System.out.println("【输入】漂移实例在基准上多成交 BTC x3（A 卖 / B 买）");
        mm.forEach(m -> System.out.println("【差异】" + m.scope() + " " + m.id()
                + " 维度=" + m.dimension() + " 期望=" + m.expected()
                + " 实际=" + m.actual()));
        assertFalse(mm.isEmpty(), "状态不一致必须能检出");

        // 必须能定位到：A/B 的现金、BTC 持仓、成交笔数与成交量；以及 BTC 标的维度
        assertTrue(mm.stream().anyMatch(m -> m.scope() == Mismatch.Scope.ACCOUNT
                && m.id().equals("A") && m.dimension().equals("cash")));
        assertTrue(mm.stream().anyMatch(m -> m.scope() == Mismatch.Scope.ACCOUNT
                && m.id().equals("B/BTC") && m.dimension().equals("position")
                && m.expected().equals("4") && m.actual().equals("7")));
        assertTrue(mm.stream().anyMatch(m -> m.scope() == Mismatch.Scope.ACCOUNT
                && m.id().equals("A") && m.dimension().equals("tradeCount")));
        assertTrue(mm.stream().anyMatch(m -> m.scope() == Mismatch.Scope.SYMBOL
                && m.id().equals("BTC") && m.dimension().equals("filledQuantity")
                && m.expected().equals("4") && m.actual().equals("7")));
        // 未受影响的账户 C 不应报差异
        assertTrue(mm.stream().noneMatch(m -> m.id().startsWith("C")),
                "未变化的账户 C 不应出现在差异里");
        System.out.println("【判定】差异精确落到 A/B 的现金、B/BTC 持仓、A 成交笔数与 BTC 成交量");
    }

    @Test
    @DisplayName("缺失账户/多余账户也能定位")
    void missingAccountIsLocated() {
        MatchingEngine base = scenarioA();
        MatchingEngine other = new MatchingEngine();
        other.registerAccount("A", new BigDecimal("100000"), 1000);
        other.registerAccount("B", new BigDecimal("100000"), 1000);
        // 故意不注册 C（账户集合不同）
        other.apply(Command.placeLimit(1, "A", "BTC", "aSell",
                Side.SELL, new BigDecimal("100"), 10));
        other.apply(Command.placeLimit(2, "B", "BTC", "bBuy",
                Side.BUY, new BigDecimal("100"), 4));

        List<Mismatch> mm = Reconciler.compare(base, other);
        System.out.println("【差异集合】");
        mm.forEach(m -> System.out.println("  " + m.scope() + " " + m.id()
                + " " + m.dimension() + " " + m.expected() + "->" + m.actual()));
        assertTrue(mm.stream().anyMatch(m -> m.scope() == Mismatch.Scope.ACCOUNT
                && m.id().equals("C") && m.dimension().equals("presence")
                && m.expected().equals("present") && m.actual().equals("absent")));
        System.out.println("【判定】缺失账户 C 被定位为 ACCOUNT/C/presence: present->absent");
    }
}
