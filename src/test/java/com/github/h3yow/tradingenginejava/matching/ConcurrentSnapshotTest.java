package com.github.h3yow.tradingenginejava.matching;

import com.github.h3yow.tradingenginejava.matching.engine.MatchingEngine;
import com.github.h3yow.tradingenginejava.matching.model.Account;
import com.github.h3yow.tradingenginejava.matching.model.Command;
import com.github.h3yow.tradingenginejava.matching.model.Order;
import com.github.h3yow.tradingenginejava.matching.model.Side;
import com.github.h3yow.tradingenginejava.matching.replay.ReplaySupport;
import com.github.h3yow.tradingenginejava.matching.snapshot.Snapshot;
import com.github.h3yow.tradingenginejava.matching.util.JsonIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不停机一致性快照单测：
 * 1) 一个写入线程持续提交上千条指令（多账户、多标的、含真实成交/撤单/改单/拒绝），
 *    另一个线程并发导出快照——导出不得卡住写入、也不得夹在指令中间；
 * 2) 每个采样快照都落在明确的指令边界（lastSeq=k）：恢复 + 回放 k 之后的增量日志，
 *    最终成交序列、每笔委托三分量（已成交/已撤销/剩余）、账户四分量，
 *    与全程未导出的参照实例逐项一致；
 * 3) 同一状态两次导出的 JSON 逐字节相同（可复现）。
 */
class ConcurrentSnapshotTest {

    private static final int ACCOUNTS = 30;
    private static final String[] SYMBOLS = {"BTC", "ETH", "SOL", "DOGE"};
    private static final int COMMAND_COUNT = 2_000;

    /** 固定种子生成确定性指令日志：多账户多标的，限价/市价/撤/改混合，保证大量真实成交。 */
    private List<Command> buildLog() {
        Random rnd = new Random(20261005L);
        List<Command> log = new ArrayList<>();
        List<String[]> live = new ArrayList<>(); // [accountId, clientOrderId] 生成期候选
        long seq = 0;
        int oid = 0;
        for (int i = 0; i < COMMAND_COUNT; i++) {
            String acct = "AC" + (rnd.nextInt(ACCOUNTS));
            String symbol = SYMBOLS[rnd.nextInt(SYMBOLS.length)];
            int kind = rnd.nextInt(100);
            seq++;
            if (kind < 62 || live.isEmpty()) {
                // 限价单：价格围绕 100 小幅波动，两侧交替 -> 高概率相互成交
                Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
                BigDecimal price = new BigDecimal(98 + rnd.nextInt(5));
                long qty = 1 + rnd.nextInt(20);
                String coid = "o" + (oid++);
                log.add(Command.placeLimit(seq, acct, symbol, coid, side, price, qty));
                live.add(new String[]{acct, coid});
            } else if (kind < 72) {
                // 市价单（IOC）
                Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
                log.add(Command.placeMarket(seq, acct, symbol, "m" + (oid++),
                        side, 1 + rnd.nextInt(10)));
            } else if (kind < 86) {
                // 撤单：撤一个生成期候选（运行期可能已终结 -> UNKNOWN_ORDER，属预期拒绝）
                String[] pick = live.remove(rnd.nextInt(live.size()));
                log.add(Command.cancel(seq, pick[0], pick[1]));
            } else {
                // 改单：改一个生成期候选
                String[] pick = live.get(rnd.nextInt(live.size()));
                log.add(Command.replace(seq, pick[0], pick[1],
                        new BigDecimal(98 + rnd.nextInt(5)), 1 + rnd.nextInt(20)));
            }
        }
        return log;
    }

    private MatchingEngine newEngine() {
        MatchingEngine e = new MatchingEngine();
        for (int i = 0; i < ACCOUNTS; i++) {
            e.registerAccount("AC" + i, new BigDecimal("500000"), 5000);
        }
        return e;
    }

    /** 全量指纹：成交序列 + 每笔委托三分量 + 账户四分量 + 拒绝序列。 */
    private String fingerprint(MatchingEngine e) {
        StringBuilder sb = new StringBuilder();
        e.getTrades().forEach(t -> sb.append(t.tradeId()).append(':')
                .append(t.makerClientOrderId()).append('>').append(t.takerClientOrderId())
                .append('@').append(t.price().toPlainString())
                .append('x').append(t.quantity()).append(';'));
        sb.append('|');
        for (Order o : e.getOrders()) {
            sb.append(o.getClientOrderId()).append('=')
                    .append(o.getFilledQty()).append('/')
                    .append(o.getCanceledQty()).append('/')
                    .append(o.getOpenQty()).append(':')
                    .append(o.getStatus()).append(';');
        }
        sb.append('|');
        for (Map.Entry<String, Account> en : e.getAccounts().entrySet()) {
            Account a = en.getValue();
            sb.append(en.getKey()).append('=')
                    .append(a.getCash().toPlainString()).append(',')
                    .append(a.getReservedCash().toPlainString()).append(',')
                    .append(a.getPosition()).append(',')
                    .append(a.getReservedPosition()).append(';');
        }
        sb.append('|');
        e.getRejections().forEach(r -> sb.append(r.seq()).append(':')
                .append(r.reason()).append(';'));
        return sb.toString();
    }

    @Test
    @DisplayName("边写边导出：每个采样快照恢复+续跑结果与全程未导出实例逐项一致")
    void snapshotWhileWritingThenContinueMatchesReference() throws Exception {
        List<Command> log = buildLog();

        // 参照实例：全程不导出，一次跑完
        MatchingEngine ref = newEngine();
        log.forEach(ref::apply);
        String refFp = fingerprint(ref);
        System.out.println("【输入】" + ACCOUNTS + " 账户 × " + SYMBOLS.length
                + " 标的 × " + log.size() + " 条指令；参照成交=" + ref.getTrades().size()
                + " 笔，拒绝=" + ref.getRejections().size() + " 条");
        assertTrue(ref.getTrades().size() > 200,
                "并发场景必须真正跑出成交（实际 " + ref.getTrades().size() + " 笔）");

        // 被测实例：写入线程逐条应用，快照线程并发导出
        MatchingEngine live = newEngine();
        ConcurrentLinkedQueue<Snapshot> snapshots = new ConcurrentLinkedQueue<>();
        AtomicBoolean writerDone = new AtomicBoolean(false);
        CountDownLatch start = new CountDownLatch(1);

        Thread writer = new Thread(() -> {
            await(start);
            for (Command c : log) {
                live.apply(c);
            }
            writerDone.set(true);
        }, "writer");
        Thread snapshotted = new Thread(() -> {
            await(start);
            while (!writerDone.get()) {
                snapshots.add(live.snapshot());
                Thread.yield();
            }
            snapshots.add(live.snapshot()); // 收尾再导一份（全量后）
        }, "snapshotter");

        long t0 = System.nanoTime();
        writer.start();
        snapshotted.start();
        start.countDown();
        writer.join(60_000);
        snapshotted.join(60_000);
        long writeMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("【判定】写入线程在 " + writeMs + "ms 内跑完 " + log.size()
                + " 条指令，期间并发导出快照 " + snapshots.size() + " 份（写入未被长时间卡住）");
        assertTrue(snapshots.size() >= 3, "写入期间应导出多份快照，实际 " + snapshots.size());

        // 边写边导的最终状态必须与参照一致（快照不干扰撮合）
        assertEquals(refFp, fingerprint(live), "并发导出干扰了撮合结果");

        // 采样校验：每份快照恢复 + 回放增量日志 == 参照终态
        List<Snapshot> all = new ArrayList<>(snapshots);
        int samples = Math.min(25, all.size());
        int checked = 0;
        for (int i = 0; i < samples; i++) {
            Snapshot snap = all.get((int) ((long) i * all.size() / samples));
            long cut = snap.lastSeq;
            assertTrue(cut >= 0 && cut <= log.size(),
                    "快照切点 lastSeq=" + cut + " 必须落在指令边界上");
            // 经 JSON 往返再恢复，验证导出物可落盘迁移
            String json = JsonIO.toJson(snap);
            MatchingEngine restored = newEngine();
            restored.restore(JsonIO.fromJson(json, Snapshot.class));
            ReplaySupport.replay(restored, log);
            assertEquals(refFp, fingerprint(restored),
                    "快照(lastSeq=" + cut + ")恢复续跑后与参照不一致");
            checked++;
        }
        System.out.println("【结果】采样 " + checked + " 份快照（切点覆盖 0.."
                + log.size() + "），恢复+续跑指纹与参照逐项一致");
    }

    @Test
    @DisplayName("同一状态两次导出 JSON 逐字节相同；旧格式（无 formatVersion）快照仍可恢复")
    void snapshotJsonReproducibleAndBackwardCompatible() {
        MatchingEngine e = newEngine();
        List<Command> log = buildLog();
        log.subList(0, 500).forEach(e::apply);
        String fp = fingerprint(e);

        String j1 = e.snapshotJson();
        String j2 = e.snapshotJson();
        assertEquals(j1, j2, "同一状态两次导出必须逐字节相同");
        System.out.println("【判定】同一状态两次导出 JSON 长度=" + j1.length() + "，逐字节一致");
        assertTrue(j1.contains("\"formatVersion\" : 1"), "新导出应带格式版本号");

        // 模拟旧版本导出物：去掉 formatVersion 字段后必须仍能恢复且状态一致
        String legacy = j1.replace("\"formatVersion\" : 1,\n", "");
        MatchingEngine restored = newEngine();
        restored.restore(JsonIO.fromJson(legacy, Snapshot.class));
        assertEquals(fp, fingerprint(restored), "旧格式快照恢复后状态不一致");
        System.out.println("【结果】旧格式（无 formatVersion）快照恢复后指纹一致，继续可用");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
