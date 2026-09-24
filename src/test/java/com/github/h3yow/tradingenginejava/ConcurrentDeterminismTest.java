package com.github.h3yow.tradingenginejava;

import com.github.h3yow.tradingenginejava.command.Command;
import com.github.h3yow.tradingenginejava.command.CommandResult;
import com.github.h3yow.tradingenginejava.engine.EngineSnapshot;
import com.github.h3yow.tradingenginejava.engine.MatchingEngine;
import com.github.h3yow.tradingenginejava.model.OrderType;
import com.github.h3yow.tradingenginejava.model.RejectReason;
import com.github.h3yow.tradingenginejava.model.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发确定性测试：
 * 同一批命令在多种线程调度下（随机打乱 + 多线程同时提交），
 * 成交序号序列、每个订单状态、各账户余额必须完全一致；
 * 且不得出现重复成交、超卖、负余额；超限委托被明确拒绝。
 */
class ConcurrentDeterminismTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentDeterminismTest.class);
    private static final String SYM = "BTC";

    /** 构造一批确定性输入：4 个账户、入金后并发提交大量互相可成交的买卖单。 */
    private List<Command> buildCommands() {
        List<Command> cmds = new ArrayList<>();
        long seq = 1;
        // A/C 持现金；B/D 持币
        cmds.add(Command.deposit(seq++, "A", 500_000L, SYM, 0L));
        cmds.add(Command.deposit(seq++, "B", 100_000L, SYM, 2_000L));
        cmds.add(Command.deposit(seq++, "C", 500_000L, SYM, 0L));
        cmds.add(Command.deposit(seq++, "D", 100_000L, SYM, 2_000L));
        // 交错买卖单（seq 决定唯一先后），包含必然现金/持仓不足与自成交的委托
        for (int i = 0; i < 200; i++) {
            String buyer = (i % 2 == 0) ? "A" : "C";
            String seller = (i % 2 == 0) ? "B" : "D";
            long price = 90 + (i % 21);
            cmds.add(Command.place(seq, seller, "s" + i, SYM, Side.SELL, OrderType.LIMIT, price, 3));
            seq++;
            cmds.add(Command.place(seq, buyer, "b" + i, SYM, Side.BUY, OrderType.LIMIT, price, 3));
            seq++;
        }
        // 越界委托：E 未开户；A 现金不足的大单；B 超出持仓的卖单；A 的自成交单
        cmds.add(Command.place(seq, "E", "ghost" + seq, SYM, Side.BUY, OrderType.LIMIT, 100, 1));
        seq++;
        cmds.add(Command.place(seq, "A", "tooMuchCash" + seq, SYM, Side.BUY, OrderType.LIMIT, 100, 100_000));
        seq++;
        cmds.add(Command.place(seq, "B", "tooMuchQty" + seq, SYM, Side.SELL, OrderType.LIMIT, 1, 100_000));
        seq++;
        cmds.add(Command.place(seq, "A", "selfBuy" + seq, SYM, Side.BUY, OrderType.LIMIT, 10_000, 1));
        seq++;
        return List.copyOf(cmds);
    }

    private String fingerprint(EngineSnapshot s) {
        StringBuilder sb = new StringBuilder();
        for (var a : s.accounts()) {
            sb.append(a.accountId()).append(":cash=").append(a.cash())
                    .append(":rc=").append(a.reservedCash())
                    .append(":pos=").append(a.positions().toString())
                    .append(":rp=").append(a.reservedPositions().toString()).append('|');
        }
        for (var o : s.allOrders().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey()).toList()) {
            sb.append(o.getKey()).append('=')
                    .append(o.getValue().getStatus()).append(':')
                    .append(o.getValue().getFilledQty()).append(':')
                    .append(o.getValue().getRemainingQty()).append('|');
        }
        sb.append("trades=");
        sb.append(s.trades().stream()
                .map(t -> t.makerOrderId() + "<-" + t.takerOrderId()
                        + "@" + t.price() + "x" + t.qty() + "#" + t.tradeSeq())
                .collect(Collectors.joining(",")));
        return sb.toString();
    }

    @Test
    @DisplayName("顺序基线：单线程按 seq 执行产生基准指纹")
    void sequentialBaseline() {
        MatchingEngine e = new MatchingEngine();
        List<CommandResult> results = e.submitBatch(buildCommands());
        log.info("[基线] 命令数={} 成交数={} 拒绝数={}",
                results.size(), e.getTrades().size(),
                results.stream().filter(r -> !r.accepted()).count());
        assertInvariants(e, results);
    }

    @RepeatedTest(30)
    @DisplayName("并发收集器：8线程随机调度提交同一批委托，成交/余额指纹与顺序基线完全一致")
    void sharedEngineConcurrentSafety() throws Exception {
        List<Command> commands = buildCommands();

        // 顺序基线
        MatchingEngine baseline = new MatchingEngine();
        List<CommandResult> baseResults = baseline.submitBatch(commands);
        String baselineFingerprint = fingerprint(baseline.snapshot());

        // 并发提交：同一批命令切成 8 份随机打乱，多线程仅并发“收集”，
        // 收集完毕后统一 finish（引擎单锁内按 seq 全局排序执行）。
        MatchingEngine concurrent = new MatchingEngine();
        MatchingEngine.Collector collector = concurrent.newCollector();

        List<Command> shuffled = new ArrayList<>(commands);
        Collections.shuffle(shuffled);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        int chunk = (shuffled.size() + threads - 1) / threads;
        for (int t = 0; t < threads; t++) {
            List<Command> part = new ArrayList<>(
                    shuffled.subList(Math.min(t * chunk, shuffled.size()),
                                     Math.min((t + 1) * chunk, shuffled.size())));
            // 再次制造线程内乱序，确保任何调度组合都不影响结果
            Collections.shuffle(part);
            pool.submit(() -> {
                try {
                    startGate.await();
                    for (Command c : part) {
                        collector.add(c);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        List<CommandResult> results = collector.finish();
        assertInvariants(concurrent, results);
        assertEquals(fingerprint(baseline.snapshot()), fingerprint(concurrent.snapshot()),
                "并发调度结果必须与顺序基线完全一致");
        // 拒绝集合也必须一致（超限委托被明确拒绝、原因可区分）
        List<RejectReason> a = baseResults.stream().filter(r -> !r.accepted())
                .map(CommandResult::reason).sorted().toList();
        List<RejectReason> b = results.stream().filter(r -> !r.accepted())
                .map(CommandResult::reason).sorted().toList();
        assertEquals(a, b, "拒绝原因集合必须一致");
        log.info("[并发收集器] threads={} 命令={} 成交={} 拒绝={} 指纹一致",
                threads, commands.size(), concurrent.getTrades().size(), b.size());
    }

    @Test
    @DisplayName("并行独立引擎：8线程各持独立引擎执行同一批打乱命令，指纹全部相同")
    void parallelIndependentEngines() throws Exception {
        List<Command> base = buildCommands();
        MatchingEngine ref = new MatchingEngine();
        ref.submitBatch(base);
        String baseline = fingerprint(ref.snapshot());

        int n = 12;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> fingerprints = ConcurrentHashMap.newKeySet();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    List<Command> shuffled = new ArrayList<>(base);
                    Collections.shuffle(shuffled);
                    MatchingEngine e = new MatchingEngine();
                    List<CommandResult> rs = e.submitBatch(shuffled);
                    assertInvariants(e, rs);
                    fingerprints.add(fingerprint(e.snapshot()));
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), "并发执行出现异常: " + errors);
        log.info("[并发] 独立引擎指纹种类数={} (期望1)", fingerprints.size());
        assertEquals(1, fingerprints.size(), "不同调度下结果必须一致");
        assertTrue(fingerprints.contains(baseline), "并发结果必须与顺序基线一致");
    }

    private void assertInvariants(MatchingEngine e, List<CommandResult> results) {
        EngineSnapshot s = e.snapshot();
        // 1. 无重复成交号
        long distinct = s.trades().stream().mapToLong(t -> t.tradeSeq()).distinct().count();
        assertEquals(s.trades().size(), distinct, "不得出现重复成交");
        // 2. 现金/持仓非负
        for (var a : s.accounts()) {
            assertTrue(a.cash() >= 0, "现金不得为负: " + a);
            assertTrue(a.reservedCash() >= 0 && a.reservedCash() <= a.cash());
            a.positions().values().forEach(v -> assertTrue(v >= 0, "持仓不得为负"));
            a.reservedPositions().values().forEach(v -> assertTrue(v >= 0));
        }
        // 3. 证券总量守恒（position 已含冻结持仓；4 账户合计 4000 BTC）
        long totalQty = s.accounts().stream()
                .mapToLong(a -> a.positions().getOrDefault(SYM, 0L)).sum();
        assertEquals(4_000L, totalQty, "持仓总量守恒（冻结持仓是持仓的子集）");
        // 4. 现金总量守恒（cash 已含 reservedCash）
        long totalCash = s.accounts().stream().mapToLong(a -> a.cash()).sum();
        assertEquals(1_200_000L, totalCash, "现金总量守恒（冻结现金是现金的子集）");
        // 5. 越界委托必须被明确拒绝（至少包含四类原因）
        Set<RejectReason> reasons = results.stream().filter(r -> !r.accepted())
                .map(CommandResult::reason).collect(Collectors.toSet());
        assertTrue(reasons.contains(RejectReason.UNKNOWN_ACCOUNT), "未知账户必须被拒: " + reasons);
        assertTrue(reasons.contains(RejectReason.INSUFFICIENT_CASH), "现金超限必须被拒: " + reasons);
        assertTrue(reasons.contains(RejectReason.INSUFFICIENT_POSITION), "持仓超限必须被拒: " + reasons);
        // 6. 每个 orderId 的成交量不超过原始量
        for (var o : s.allOrders().values()) {
            assertTrue(o.getFilledQty() <= o.getOriginalQty(),
                    "不得超卖: " + o.getOrderId());
        }
    }
}
