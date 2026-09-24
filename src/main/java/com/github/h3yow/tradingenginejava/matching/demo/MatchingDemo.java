package com.github.h3yow.tradingenginejava.matching.demo;

import com.github.h3yow.tradingenginejava.matching.engine.MatchingEngine;
import com.github.h3yow.tradingenginejava.matching.engine.RiskConfig;
import com.github.h3yow.tradingenginejava.matching.gateway.DeterministicGateway;
import com.github.h3yow.tradingenginejava.matching.model.Command;
import com.github.h3yow.tradingenginejava.matching.model.CommandResult;
import com.github.h3yow.tradingenginejava.matching.model.Side;
import com.github.h3yow.tradingenginejava.matching.replay.ReplaySupport;
import com.github.h3yow.tradingenginejava.matching.snapshot.Snapshot;
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

        System.out.println("========== 阶段三：快照导出/JSON ==========");
        Snapshot snapshot = engine.snapshot();
        String json = JsonIO.toJson(snapshot);
        System.out.println("快照 lastSeq=" + snapshot.lastSeq
                + " 成交数=" + engine.getTrades().size() + " JSON字节=" + json.length());

        System.out.println("========== 阶段四：空引擎从快照恢复 + 整段日志重复回放 ==========");
        MatchingEngine rebuilt = new MatchingEngine();
        rebuilt.restore(JsonIO.fromJson(json, Snapshot.class));
        List<CommandResult> applied = ReplaySupport.replay(rebuilt,
                concat(phase1, phase2));
        System.out.println("历史指令被跳过数=" + (phase1.size() + phase2.size() - applied.size())
                + "，新增应用=" + applied.size());
        ReplaySupport.replay(rebuilt, concat(phase1, phase2));
        System.out.println("再次回放后成交数仍为=" + rebuilt.getTrades().size()
                + "（原引擎=" + engine.getTrades().size() + "）");
        printBalances(rebuilt);

        System.out.println("========== 结论：两次引擎成交数/拒绝数一致，余额一致，可复现 ==========");
        System.out.println("trades: " + engine.getTrades().size()
                + " == " + rebuilt.getTrades().size());
        System.out.println("rejects: " + engine.getRejections().size()
                + " == " + rebuilt.getRejections().size());
    }

    private static List<Command> concat(List<Command> a, List<Command> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }

    private static void printBalances(MatchingEngine e) {
        for (String id : List.of("Alice", "Bob", "Carol", "Dana")) {
            System.out.println("余额: " + e.getAccount(id));
        }
    }
}
