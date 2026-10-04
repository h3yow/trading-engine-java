package com.github.h3yow.tradingenginejava.matching.demo;

import com.github.h3yow.tradingenginejava.matching.engine.MatchingEngine;
import com.github.h3yow.tradingenginejava.matching.engine.RiskConfig;
import com.github.h3yow.tradingenginejava.matching.gateway.DeterministicGateway;
import com.github.h3yow.tradingenginejava.matching.gateway.StreamingGateway;
import com.github.h3yow.tradingenginejava.matching.model.BatchResult;
import com.github.h3yow.tradingenginejava.matching.model.Command;
import com.github.h3yow.tradingenginejava.matching.model.CommandResult;
import com.github.h3yow.tradingenginejava.matching.model.Side;
import com.github.h3yow.tradingenginejava.matching.reconcile.Mismatch;
import com.github.h3yow.tradingenginejava.matching.reconcile.Reconciler;
import com.github.h3yow.tradingenginejava.matching.snapshot.Snapshot;
import com.github.h3yow.tradingenginejava.matching.snapshot.SnapshotExport;
import com.github.h3yow.tradingenginejava.matching.util.JsonIO;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 本地可运行的端到端演示：多账户限价/市价撮合 → 快照导出 → 空引擎恢复 → 增量回放，
 * 逐步打印输入、判定依据、成交、余额，并用“重新跑一遍”验证结论可复现。
 *
 * <p>运行：{@code mvn -q exec:java} 不便，直接用
 * {@code mvn -q compile exec:java -Dexec.mainClass=...MatchingDemo}（需 exec 插件），
 * 或在 IDE 中运行本类 main。
 */
public final class MatchingDemo {

    private MatchingDemo() {
    }

    public static void main(String[] args) {
        MatchingEngine engine = new MatchingEngine(
                new RiskConfig(20, new BigDecimal("1000000")));
        engine.registerAccount("Alice", new BigDecimal("100000"), 1000);
        engine.registerAccount("Bob", new BigDecimal("100000"), 1000);
        engine.registerAccount("Carol", new BigDecimal("300"), 0);
        engine.registerAccount("Dana", new BigDecimal("100000"), 1000);

        long s = 0;
        List<Command> phase1 = List.of(
                // Alice 卖10@100；Bob 买4@100 部分成交 Alice
                Command.placeLimit(++s, "Alice", "BTC", "A-sell-1",
                        Side.SELL, new BigDecimal("100"), 10),
                Command.placeLimit(++s, "Bob", "BTC", "B-buy-1",
                        Side.BUY, new BigDecimal("100"), 4),
                // Carol 市价买：只有 300 元，100 元/股最多 3 股，余量 IOC 撤销
                Command.placeMarket(++s, "Carol", "BTC", "C-mkt-1",
                        Side.BUY, 10),
                // Alice 改单：已成交7，剩余3改价99、总量8（余量1）
                Command.replace(++s, "Alice", "A-sell-1",
                        new BigDecimal("99"), 8)
        );

        System.out.println("========== 阶段一：顺序指令 ==========");
        for (Command c : phase1) {
            System.out.println("输入: " + c);
            CommandResult r = engine.apply(c);
            System.out.println("判定: " + r);
            r.trades().forEach(t -> System.out.println("      成交 "
                    + t.makerClientOrderId() + "->" + t.takerClientOrderId()
                    + " " + t.quantity() + "@" + t.price()
                    + " 买" + t.buyerAccountId() + " 卖" + t.sellerAccountId()));
        }
        printBalances(engine);

        System.out.println("========== 阶段二：并发乱序批次（不同调度结果一致） ==========");
        // Bob 卖2@99，Carol 买2@99 立即吃单成交；Bob 再挂远价买单并撤掉；
        // 最后 Bob 再下一个天价买单（超过风控名义金额）-> LIMIT_EXCEEDED 明确拒绝。
        // 5 条指令由不同虚拟线程在同一栅栏后并发提交，实际进入顺序由 JVM 调度决定，结论不变。
        List<Command> phase2 = List.of(
                Command.placeLimit(++s, "Bob", "BTC", "B-sell-2",
                        Side.SELL, new BigDecimal("99"), 2),
                Command.placeLimit(++s, "Dana", "BTC", "D-buy-2",
                        Side.BUY, new BigDecimal("99"), 2),
                Command.placeLimit(++s, "Bob", "BTC", "B-rest",
                        Side.BUY, new BigDecimal("90"), 1),
                Command.cancel(++s, "Bob", "B-rest"),
                Command.placeLimit(++s, "Bob", "BTC", "B-too-big",
                        Side.BUY, new BigDecimal("1000000"), 100)
        );
        Map<Long, CommandResult> cr =
                new DeterministicGateway(engine).submitBatch(phase2, 42L);
        cr.values().forEach(r -> System.out.println("判定: " + r));
        printBalances(engine);

        System.out.println("========== 阶段三：委托组原子提交（成功 + 整组回滚对比） ==========");
        // 成功组：Dana 买2@99 成交，再挂买1@90（两条各自的撮合结果都可单独查）
        List<Command> goodGroup = List.of(
                Command.placeLimit(++s, "Dana", "BTC", "D-g1",
                        Side.BUY, new BigDecimal("99"), 2),
                Command.placeLimit(++s, "Dana", "BTC", "D-g2",
                        Side.BUY, new BigDecimal("90"), 1));
        BatchResult good = engine.applyAtomicBatch(goodGroup);
        System.out.println("成功组 committed=" + good.committed()
                + "，逐单结果数=" + good.results().size() + "：");
        good.results().forEach(r -> System.out.println("  判定: " + r));

        // 回滚组：第1条会成交，第2条 Carol 现金不足 -> 整组回滚、零残留
        long rollbackStart = s + 1;
        List<Command> badGroup = List.of(
                Command.placeLimit(rollbackStart, "Dana", "BTC", "D-bad1",
                        Side.BUY, new BigDecimal("99"), 1),
                Command.placeLimit(rollbackStart + 1, "Carol", "BTC", "D-bad2",
                        Side.BUY, new BigDecimal("99"), 1000));
        BatchResult bad = engine.applyAtomicBatch(badGroup);
        System.out.println("回滚组 committed=" + bad.committed()
                + "，批次级原因=" + bad.batchRejection()
                + "，成员原因=" + (bad.memberRejection() == null ? "-"
                : bad.memberRejection().reason())
                + "，水位仍=" + engine.getLastSeq()
                + "，D-bad1 残留=" + (engine.getOrder("D-bad1") != null));

        System.out.println("========== 阶段四：不停机一致性快照（边写边导） ==========");
        MatchingEngine continued;
        SnapshotExport onlineExport;
        try (StreamingGateway gw = new StreamingGateway(engine)) {
            gw.prime(engine.getLastSeq() + 1);
            // 持续写入 3 条（成交 + 撤单），全部落地后在写入流中立即导出；
            // 导出请求与写入走同一条全序队列，屏障点恰好是这 3 条之后的指令边界
            List<Command> phase4 = List.of(
                    Command.placeLimit(++s, "Bob", "BTC", "B-sell-4",
                            Side.SELL, new BigDecimal("90"), 2),
                    Command.placeLimit(++s, "Alice", "BTC", "A-buy-4",
                            Side.BUY, new BigDecimal("90"), 1),
                    Command.cancel(++s, "Dana", "D-g2"));
            phase4.forEach(c -> System.out.println("写入中: " + c));
            phase4.forEach(c -> gw.submit(c).join());
            onlineExport = gw.exportSnapshot();
            System.out.println("快照边界 lastSeq=" + onlineExport.lastSeq()
                    + "（写入不停机，JSON 字节=" + onlineExport.json().length()
                    + "，采集耗时微秒=" + onlineExport.captureNanos() / 1000 + "）");
        }

        System.out.println("========== 阶段五：从在线快照恢复 + 增量回放，与原引擎对账 ==========");
        continued = new MatchingEngine();
        continued.restore(JsonIO.fromJson(onlineExport.json(), Snapshot.class));
        // 阶段五在新实例上再续跑新指令（继续撮合 + 撤改）
        List<Command> phase5 = List.of(
                Command.placeLimit(++s, "Bob", "ETH", "B-ETH-s",
                        Side.SELL, new BigDecimal("10"), 5),
                Command.placeLimit(++s, "Alice", "ETH", "A-ETH-b",
                        Side.BUY, new BigDecimal("10"), 5),
                Command.replace(++s, "Bob", "B-ETH-s", new BigDecimal("11"), 5));
        phase5.forEach(continued::apply);
        // 原引擎执行完全相同的后续指令，用于对账
        phase5.forEach(engine::apply);

        List<Mismatch> mismatches = Reconciler.compare(engine, continued);
        String summaryJson = JsonIO.toJson(Reconciler.summarize(continued));
        String summaryAgain = JsonIO.toJson(Reconciler.summarize(continued));
        System.out.println("对账差异数=" + mismatches.size()
                + "（0=两实例状态一致），摘要可复现=" + summaryJson.equals(summaryAgain));
        mismatches.forEach(m -> System.out.println("  差异: " + m));
        printBalances(engine);

        System.out.println("========== 结论：成交数/拒绝数一致，余额一致，对账无差异 ==========");
        System.out.println("trades: " + engine.getTrades().size()
                + " == " + continued.getTrades().size());
        System.out.println("rejects: " + engine.getRejections().size()
                + " == " + continued.getRejections().size());
    }

    private static void printBalances(MatchingEngine e) {
        for (String id : List.of("Alice", "Bob", "Carol", "Dana")) {
            System.out.println("余额: " + e.getAccount(id));
        }
    }
}
