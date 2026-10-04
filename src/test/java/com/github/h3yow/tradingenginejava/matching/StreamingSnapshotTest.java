package com.github.h3yow.tradingenginejava.matching;

import com.github.h3yow.tradingenginejava.matching.engine.MatchingEngine;
import com.github.h3yow.tradingenginejava.matching.engine.RiskConfig;
import com.github.h3yow.tradingenginejava.matching.gateway.StreamingGateway;
import com.github.h3yow.tradingenginejava.matching.model.Command;
import com.github.h3yow.tradingenginejava.matching.model.CommandResult;
import com.github.h3yow.tradingenginejava.matching.model.Order;
import com.github.h3yow.tradingenginejava.matching.model.Account;
import com.github.h3yow.tradingenginejava.matching.model.Side;
import com.github.h3yow.tradingenginejava.matching.replay.ReplaySupport;
import com.github.h3yow.tradingenginejava.matching.snapshot.SnapshotExport;
import com.github.h3yow.tradingenginejava.matching.util.JsonIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不停机一致性快照单测。
 *
 * <p>核心断言：多账户边写边导出时，快照严格落在某条指令边界（lastSeq 明确、不夹指令中间）；
 * 拿快照到新实例恢复后续跑（继续撮合、撤单、改单），最终与“全程没有导出过”的实例逐项一致：
 * 每笔委托的已成交/已撤销/剩余数量拆分、账户四个分量、成交序列全部相同。
 */
class StreamingSnapshotTest {

    private static final int ACCOUNTS = 24;
    private static final String[] SYMBOLS = {"BTC", "ETH", "SOL", "XAG"};

    /** 构造一段确定的、上千条的混合指令日志（限价/市价/撤单/改单交错）。 */
    private List<Command> buildLog() {
        List<Command> log = new ArrayList<>();
        java.util.Set<String> replaced = new java.util.HashSet<>();
        long s = 0;
        // 先铺一层买卖挂单制造对手盘
        for (int a = 0; a < ACCOUNTS; a++) {
            String me = "acct" + a;
            for (String sym : SYMBOLS) {
                long px = 100 + (a % 7);
                log.add(Command.placeLimit(++s, me, sym, me + "-" + sym + "-seed",
                        (a % 2 == 0) ? Side.SELL : Side.BUY,
                        new BigDecimal(px), 20));
            }
        }
        // 主体：吃单、余量挂簿、撤单、改单交替；改单只作用于尚未改过的种子单（每笔至多一次）
        int rounds = 12;
        for (int r = 0; r < rounds; r++) {
            for (int a = 0; a < ACCOUNTS; a++) {
                String me = "acct" + a;
                String sym = SYMBOLS[(a + r) % SYMBOLS.length];
                long px = 95 + ((a + r) % 12);
                switch ((a + r) % 4) {
                    case 0 -> // 限价吃单/挂簿
                            log.add(Command.placeLimit(++s, me, sym,
                                    me + "-" + r + "-L", Side.BUY,
                                    new BigDecimal(px + 6), 5));
                    case 1 -> // 市价吃单（无对手盘则被拒，允许）
                            log.add(Command.placeMarket(++s, me, sym,
                                    me + "-" + r + "-M", Side.SELL, 4));
                    case 2 -> { // 限价 + 立刻撤
                        String coid = me + "-" + r + "-C";
                        log.add(Command.placeLimit(++s, me, sym, coid,
                                Side.SELL, new BigDecimal(500 + r), 3));
                        log.add(Command.cancel(++s, me, coid));
                    }
                    default -> { // 改自己某个尚未改过的种子单（加量到 22，至多改一次）
                        String coid = me + "-" + sym + "-seed";
                        if (replaced.add(coid)) {
                            log.add(Command.replace(++s, me, coid,
                                    new BigDecimal(100 + ((a + r + 1) % 7)), 22));
                        } else {
                            log.add(Command.placeLimit(++s, me, sym,
                                    me + "-" + r + "-R", Side.BUY,
                                    new BigDecimal(px + 6), 2));
                        }
                    }
                }
            }
        }
        return log;
    }

    private MatchingEngine newEngine() {
        MatchingEngine e = new MatchingEngine(
                new RiskConfig(500, new BigDecimal("1000000000")));
        for (int i = 0; i < ACCOUNTS; i++) {
            e.registerAccount("acct" + i, new BigDecimal("10000000"), 1_000_000);
        }
        return e;
    }

    /** 状态指纹：账户四分量 + 每笔委托数量拆分 + 成交序列 + 水位/编号水位。 */
    private String fingerprint(MatchingEngine e, List<Command> allLog) {
        StringBuilder sb = new StringBuilder();
        sb.append("lastSeq=").append(e.getLastSeq())
                .append(",nextOrder=").append(e.getNextOrderId())
                .append(",nextTrade=").append(e.getNextTradeId()).append('|');
        for (int i = 0; i < ACCOUNTS; i++) {
            Account a = e.getAccount("acct" + i);
            sb.append(a.getAccountId()).append(':')
                    .append(a.getCash().toPlainString()).append('/')
                    .append(a.getReservedCash().toPlainString()).append('/')
                    .append(a.getPosition()).append('/')
                    .append(a.getReservedPosition()).append(';');
        }
        sb.append('|');
        for (Command c : allLog) {
            Order o = e.getOrder(c.getClientOrderId());
            if (o == null) {
                sb.append(c.getClientOrderId()).append("=ABSENT;");
                continue;
            }
            sb.append(o.getClientOrderId()).append('=')
                    .append(o.getStatus()).append(":t").append(o.getTotalQty())
                    .append(":f").append(o.getFilledQty())
                    .append(":x").append(o.getCanceledQty())
                    .append(":o").append(o.getOpenQty()).append(';');
        }
        sb.append('|');
        e.getTrades().forEach(t -> sb.append(t.tradeId()).append(':')
                .append(t.makerClientOrderId()).append('>')
                .append(t.takerClientOrderId()).append('@')
                .append(t.price().toPlainString()).append('x')
                .append(t.quantity()).append(';'));
        return sb.toString();
    }

    @Test
    @DisplayName("边写边导出：快照边界恰好是某个 lastSeq，不夹在指令中间，采集不长期卡写入")
    void concurrentExportLandsOnExactBoundaryAndDoesNotBlockWrites() throws Exception {
        List<Command> log = buildLog();
        MatchingEngine live = newEngine();
        final int exportEvery = 37; // 写几十条就导出一次，多次与写入并发交错

        try (StreamingGateway gw = new StreamingGateway(live)) {
            gw.prime(1);
            List<SnapshotExport> exports = java.util.Collections.synchronizedList(new ArrayList<>());
            int targetExports = Math.max(8, log.size() / exportEvery);

            // 持续导出线程：与写入线程同时跑，导出不等待写入、写入也不等待导出
            Thread exporter = new Thread(() -> {
                try {
                    while (exports.size() < targetExports) {
                        SnapshotExport ex = gw.exportSnapshotAsync().get();
                        if (ex.lastSeq() >= log.size()) {
                            exports.add(ex);
                            return;
                        }
                        exports.add(ex);
                    }
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }
            }, "exporter");

            List<CompletableFuture<CommandResult>> futures = new ArrayList<>();
            exporter.start();
            for (Command c : log) {
                futures.add(gw.submit(c));
                if (futures.size() % exportEvery == 0) {
                    // 写线程自己也周期性同步导出，制造两类线程的交错导出
                    SnapshotExport mid = gw.exportSnapshot();
                    exports.add(mid);
                }
            }
            for (var f : futures) {
                f.join();
            }
            exporter.join(30_000);
            SnapshotExport last = gw.exportSnapshot();
            exports.add(last);

            System.out.println("【输入】" + log.size() + " 条指令持续写入，期间并发导出 "
                    + exports.size() + " 次（" + ACCOUNTS + " 账户 × "
                    + SYMBOLS.length + " 标的）");

            // 每个快照都必须是明确边界：水位单调推进；且在导出完成的那一刻，
            // 快照 JSON 中记录的成交数与引擎当时该水位的真实成交数一致（边界不夹指令）。
            SnapshotExport prev = null;
            for (SnapshotExport ex : exports) {
                assertTrue(ex.lastSeq() >= 0);
                if (prev != null) {
                    assertTrue(ex.lastSeq() >= prev.lastSeq(),
                            "快照边界不得倒退: " + prev.lastSeq() + " -> " + ex.lastSeq());
                }
                int tradeCountInJson = countOccurrences(ex.json(), "\"tradeId\"");
                int snapshotTradeCount = (int) (long) (Integer)
                        ((java.util.Map<?, ?>) ex.snapshot().trades).get("count");
                assertEquals(snapshotTradeCount, tradeCountInJson,
                        "JSON 成交数与快照结构不一致，边界可能落在指令中间");
                prev = ex;
            }
            // 最终快照覆盖全部指令
            assertEquals(log.size(), last.lastSeq());
            System.out.println("【判定】全部导出边界单调推进，最终边界 lastSeq=" + last.lastSeq()
                    + "，采集中位耗时(微秒)=" + medianCaptureMicros(exports)
                    + "，最大(微秒)=" + maxCaptureMicros(exports));
            // 采集为纯内存拷贝，单次持锁耗时应远低于百毫秒，不长时间卡写入
            for (SnapshotExport ex : exports) {
                assertTrue(ex.captureNanos() < 200_000_000L,
                        "单次采集持锁时间异常: " + ex.captureNanos() + "ns");
            }
        }
        System.out.println("【结果】写入与导出全程并发，引擎最终成交数="
                + live.getTrades().size() + " 拒绝数=" + live.getRejections().size());
        assertTrue(live.getTrades().size() > 0, "必须真正跑出成交，不能只有拒绝");
    }

    private long maxCaptureMicros(List<SnapshotExport> exports) {
        return exports.stream().mapToLong(e -> e.captureNanos() / 1000).max().orElse(0L);
    }

    private long medianCaptureMicros(List<SnapshotExport> exports) {
        List<Long> xs = exports.stream().map(e -> e.captureNanos() / 1000).sorted().toList();
        return xs.isEmpty() ? 0 : xs.get(xs.size() / 2);
    }

    private int countOccurrences(String text, String token) {
        int n = 0, i = 0;
        while ((i = text.indexOf(token, i)) >= 0) {
            n++;
            i += token.length();
        }
        return n;
    }

    @Test
    @DisplayName("边写边导出后恢复续跑：与从未导出的实例在每笔委托数量拆分和账户余额上逐项一致")
    void exportDuringWritesThenRestoreAndContinueMatchesUnexportedRun() throws Exception {
        List<Command> log = buildLog();
        int cut = log.size() * 2 / 3; // 约 2/3 处导出，之后继续撮合/撤改

        // 参照实例：从头到尾不导出
        MatchingEngine ref = newEngine();
        for (Command c : log) {
            ref.apply(c);
        }
        String refFp = fingerprint(ref, log);

        // 在线实例：前 cut 条提交完成后，让另一写线程先放入 5 条“cut 之后”的指令，
        // 其余指令刻意留在线程里不提交，此刻导出 -> 快照恰好落在 cut+5 边界，
        // 且之后仍有真实写入在途（导出夹在连续写入之间，而不是跑完再导）。
        MatchingEngine live = newEngine();
        int head = 5;
        SnapshotExport taken;
        try (StreamingGateway gw = new StreamingGateway(live)) {
            gw.prime(1);
            for (Command c : log.subList(0, cut)) {
                gw.submit(c);
            }
            // 等到前 cut 条全部落地（明确边界 = cut）
            while (live.getLastSeq() < cut) {
                Thread.sleep(1);
            }
            CountDownLatch headSubmitted = new CountDownLatch(1);
            CountDownLatch releaseRest = new CountDownLatch(1);
            Thread lateWriter = new Thread(() -> {
                List<CompletableFuture<CommandResult>> headF = new ArrayList<>();
                for (Command c : log.subList(cut, cut + head)) {
                    headF.add(gw.submit(c));
                }
                headSubmitted.countDown();
                try {
                    releaseRest.await(); // 剩余指令在导出之后才允许入队
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (Command c : log.subList(cut + head, log.size())) {
                    gw.submit(c);
                }
            });
            lateWriter.start();
            headSubmitted.await();
            // 等这 5 条落地后立刻导出：边界必须是 cut+5，且后续指令尚未入队
            long want = cut + head;
            while (live.getLastSeq() < want) {
                Thread.sleep(1);
            }
            taken = gw.exportSnapshot();
            releaseRest.countDown(); // 放行剩余写入
            lateWriter.join();
            while (live.getLastSeq() < log.size()) {
                Thread.sleep(1);
            }
        }
        assertEquals(cut + head, taken.lastSeq(),
                "快照必须恰好落在 cut+" + head + " 的指令边界，不早一笔也不晚一笔");
        System.out.println("【输入】在线实例在 lastSeq=" + taken.lastSeq()
                + " 处并发导出（之后仍有 " + (log.size() - cut - head)
                + " 条在途写入），随后继续撮合/撤单/改单");

        // 用导出物在新实例恢复，再回放 cut 之后的增量日志
        MatchingEngine rebuilt = new MatchingEngine(
                new RiskConfig(500, new BigDecimal("1000000000")));
        rebuilt.restore(JsonIO.fromJson(taken.json(),
                com.github.h3yow.tradingenginejava.matching.snapshot.Snapshot.class));
        List<CommandResult> applied = ReplaySupport.replay(rebuilt, log);
        assertEquals(log.size() - (cut + head), applied.size(),
                "仅快照边界之后的增量应被新应用");

        String rebuiltFp = fingerprint(rebuilt, log);
        System.out.println("【判定】参照成交数=" + ref.getTrades().size()
                + " 恢复续跑成交数=" + rebuilt.getTrades().size());
        assertEquals(refFp, rebuiltFp,
                "恢复续跑后与未导出实例不一致（数量拆分/余额/成交序列）");

        // 再直接对“在线实例”本身做指纹比对：它导出后继续写，也必须与参照一致
        assertEquals(refFp, fingerprint(live, log),
                "在线实例导出后续跑结果与参照不一致");

        // 抽一笔委托逐项核对 已成交/已撤销/剩余
        Order ro = ref.getOrder("acct0-BTC-seed");
        Order no = rebuilt.getOrder("acct0-BTC-seed");
        assertNotNull(ro);
        assertEquals(ro.getFilledQty(), no.getFilledQty());
        assertEquals(ro.getCanceledQty(), no.getCanceledQty());
        assertEquals(ro.getOpenQty(), no.getOpenQty());
        assertEquals(ro.getStatus(), no.getStatus());
        System.out.println("【结果】抽样委托 acct0-BTC-seed 数量拆分 total/filled/canceled/open = "
                + no.getTotalQty() + "/" + no.getFilledQty() + "/"
                + no.getCanceledQty() + "/" + no.getOpenQty()
                + "，两实例与在线实例三方一致");
    }
}
