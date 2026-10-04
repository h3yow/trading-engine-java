package com.github.h3yow.tradingenginejava.matching;

import com.github.h3yow.tradingenginejava.matching.digest.ReconciliationDigest;
import com.github.h3yow.tradingenginejava.matching.digest.ReconciliationDigest.Difference;
import com.github.h3yow.tradingenginejava.matching.engine.MatchingEngine;
import com.github.h3yow.tradingenginejava.matching.model.Command;
import com.github.h3yow.tradingenginejava.matching.model.Side;
import com.github.h3yow.tradingenginejava.matching.replay.ReplaySupport;
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
 * 盘后对账摘要单测：
 * 1) 同一状态两次导出摘要逐字节相同（可复现）；相同日志两台实例摘要一致（hash 相等）；
 * 2) 状态不一致时 diff 精确定位到账户/标的/维度（现金、持仓、订单数、成交笔数、成交量）；
 * 3) 快照恢复后的实例与原实例摘要一致（摘要可跨实例对账）。
 */
class ReconciliationDigestTest {

    private MatchingEngine newEngine() {
        MatchingEngine e = new MatchingEngine();
        e.registerAccount("A", new BigDecimal("100000"), 1000);
        e.registerAccount("B", new BigDecimal("100000"), 1000);
        e.registerAccount("C", new BigDecimal("80000"), 500);
        return e;
    }

    private List<Command> buildLog() {
        return List.of(
                Command.placeLimit(1, "A", "BTC", "s1", Side.SELL, new BigDecimal("100"), 5),
                Command.placeLimit(2, "B", "BTC", "b1", Side.BUY, new BigDecimal("100"), 2),
                Command.placeLimit(3, "C", "ETH", "s2", Side.SELL, new BigDecimal("50"), 10),
                Command.placeLimit(4, "A", "ETH", "b2", Side.BUY, new BigDecimal("50"), 4),
                Command.cancel(5, "A", "s1"));
    }

    @Test
    @DisplayName("摘要可复现：同状态两次导出逐字节相同；相同日志两实例 hash 相等")
    void digestIsReproducible() {
        MatchingEngine e1 = newEngine();
        MatchingEngine e2 = newEngine();
        List<Command> log = buildLog();
        log.forEach(e1::apply);
        log.forEach(e2::apply);

        ReconciliationDigest d1 = e1.reconciliationDigest();
        ReconciliationDigest d1again = e1.reconciliationDigest();
        ReconciliationDigest d2 = e2.reconciliationDigest();

        System.out.println("【输入】两台实例跑同一日志（含 BTC/ETH 成交与撤单）");
        assertEquals(d1.toJson(), d1again.toJson(), "同一状态两次导出必须逐字节相同");
        assertEquals(d1.hash(), d1again.hash());
        assertEquals(d1.hash(), d2.hash(), "相同日志两实例摘要指纹必须相等");
        assertTrue(d1.diff(d2).isEmpty(), "相同状态 diff 必须为空");
        System.out.println("【判定】摘要 hash=" + d1.hash().substring(0, 16) + "...");
        System.out.println("【判定】A 账户行=" + d1.accounts.get("A"));
        System.out.println("【判定】A×BTC 行=" + d1.accountSymbols.get("A").get("BTC"));
        System.out.println("【结果】摘要可复现，一致判定通过");

        // 口径抽查：A 在 BTC 上 1 笔订单（s1，已撤）、1 笔成交（卖 2）
        ReconciliationDigest.SymbolLine aBtc = d1.accountSymbols.get("A").get("BTC");
        assertEquals(1, aBtc.orderCount());
        assertEquals(1, aBtc.tradeCount());
        assertEquals(0, aBtc.buyQty());
        assertEquals(2, aBtc.sellQty());
    }

    @Test
    @DisplayName("摘要不一致：diff 定位到具体账户/标的/维度")
    void digestDiffLocatesMismatch() {
        MatchingEngine left = newEngine();
        MatchingEngine right = newEngine();
        List<Command> log = buildLog();
        log.forEach(left::apply);
        log.forEach(right::apply);
        // 右侧多跑一笔：A 买 1 手 ETH（吃 C 的 s2 余量 6@50）->
        // A×ETH 买入量/成交笔数、C×ETH 卖出量/成交笔数、双方现金/持仓都会偏
        right.apply(Command.placeLimit(6, "A", "ETH", "b9", Side.BUY, new BigDecimal("50"), 1));

        ReconciliationDigest dl = left.reconciliationDigest();
        ReconciliationDigest dr = right.reconciliationDigest();
        assertFalse(dl.hash().equals(dr.hash()), "状态不同则摘要指纹必须不同");

        List<Difference> diffs = dl.diff(dr);
        System.out.println("【输入】右侧多一笔 A 买 1@50 ETH（吃 C 的 s2 余量）");
        diffs.forEach(d -> System.out.println("【判定】" + d));
        assertFalse(diffs.isEmpty());

        // 必须能定位到：lastSeq、A/C 的现金与持仓、A×ETH 买入量、C×ETH 卖出量与成交笔数
        assertHasDiff(diffs, "*", null, "lastSeq");
        assertHasDiff(diffs, "A", null, "cash");
        assertHasDiff(diffs, "A", null, "position");
        assertHasDiff(diffs, "C", null, "cash");
        assertHasDiff(diffs, "C", null, "position");
        assertHasDiff(diffs, "A", "ETH", "buyQty");
        assertHasDiff(diffs, "A", "ETH", "tradeCount");
        assertHasDiff(diffs, "C", "ETH", "sellQty");
        // 未受影响的维度不得误报：B 的账户行、A×BTC 行、C 的 BTC 维度
        assertNoDiff(diffs, "B", null, "cash");
        assertNoDiff(diffs, "A", "BTC", "sellQty");
        assertNoDiff(diffs, "C", "BTC", "orderCount");
        System.out.println("【结果】共 " + diffs.size()
                + " 处差异，全部定位到账户/标的/维度，未受影响维度零误报");
    }

    @Test
    @DisplayName("跨实例对账：快照恢复后的实例与原实例摘要逐项一致")
    void digestMatchesAcrossSnapshotRestore() {
        MatchingEngine e = newEngine();
        List<Command> log = buildLog();
        log.forEach(e::apply);
        ReconciliationDigest before = e.reconciliationDigest();

        // 经 JSON 快照迁移到另一实例，再继续跑一笔，两边同步续跑后摘要仍一致
        String json = e.snapshotJson();
        MatchingEngine restored = newEngine();
        restored.restore(JsonIO.fromJson(json, Snapshot.class));
        ReconciliationDigest after = restored.reconciliationDigest();
        assertEquals(before.hash(), after.hash(), "快照恢复前后摘要必须一致");
        assertTrue(before.diff(after).isEmpty());

        Command next = Command.placeLimit(6, "B", "BTC", "b7",
                Side.BUY, new BigDecimal("100"), 1);
        e.apply(next);
        ReplaySupport.replay(restored, List.of(next));
        assertEquals(e.reconciliationDigest().hash(),
                restored.reconciliationDigest().hash(), "续跑后两实例摘要必须一致");
        System.out.println("【结果】快照迁移 + 同步续跑，两实例摘要 hash 一致="
                + e.reconciliationDigest().hash().substring(0, 16) + "...");
    }

    private static void assertHasDiff(List<Difference> diffs, String acct,
                                      String sym, String dim) {
        assertTrue(diffs.stream().anyMatch(d -> d.accountId().equals(acct)
                        && java.util.Objects.equals(d.symbol(), sym)
                        && d.dimension().equals(dim)),
                "应检出差异: " + acct + "/" + sym + "/" + dim + "，实际=" + diffs);
    }

    private static void assertNoDiff(List<Difference> diffs, String acct,
                                     String sym, String dim) {
        assertTrue(diffs.stream().noneMatch(d -> d.accountId().equals(acct)
                        && java.util.Objects.equals(d.symbol(), sym)
                        && d.dimension().equals(dim)),
                "不应检出差异: " + acct + "/" + sym + "/" + dim + "，实际=" + diffs);
    }
}
