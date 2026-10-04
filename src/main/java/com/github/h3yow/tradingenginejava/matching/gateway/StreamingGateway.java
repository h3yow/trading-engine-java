package com.github.h3yow.tradingenginejava.matching.gateway;

import com.github.h3yow.tradingenginejava.matching.engine.MatchingEngine;
import com.github.h3yow.tradingenginejava.matching.model.BatchResult;
import com.github.h3yow.tradingenginejava.matching.model.Command;
import com.github.h3yow.tradingenginejava.matching.model.CommandResult;
import com.github.h3yow.tradingenginejava.matching.model.RejectReason;
import com.github.h3yow.tradingenginejava.matching.model.Rejection;
import com.github.h3yow.tradingenginejava.matching.snapshot.Snapshot;
import com.github.h3yow.tradingenginejava.matching.snapshot.SnapshotExport;
import com.github.h3yow.tradingenginejava.matching.util.JsonIO;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 不停机的一致性快照网关。
 *
 * <p>多个账户可从任意线程并发提交指令（允许乱序到达）、原子委托组，也可在写入持续进行时
 * 请求快照导出。所有“写入/提交”和“导出”进入<b>同一条全序队列</b>，由唯一消费者线程处理：
 * <ul>
 *   <li>单条指令与原子委托组都按 seq 缓冲，只在与引擎水位严丝合缝连续时才交引擎执行
 *       （乱序不报错，等缺号补齐）；原子组到期时整体执行，组内不会插入任何其他指令；
 *       重复 seq、seq 与待处理组区间重叠等情况以显式拒绝/future 异常返回，绝不静默丢弃；</li>
 *   <li>导出请求是全序中的一个<b>屏障点</b>：消费者先把该请求入队前已排队的全部提交排空处理，
 *       再在引擎 {@code guard} 内做一次纯内存采集（只构建不可变副本、不做 IO，耗时为
 *       亚毫秒~毫秒级），随后<b>在锁外、独占线程上</b>序列化为 JSON，因此导出不长时间卡住写入；</li>
 *   <li>快照严格落在撮合顺序的一个明确指令边界上——不夹在任何指令处理中间，
 *       也不会早一笔或晚一笔——其水位 {@code SnapshotExport.lastSeq()} 即该边界。</li>
 * </ul>
 *
 * <p><b>全序起点：</b>开放提交前可 {@link #prime(long)} 显式声明首条 seq（与
 * {@link DeterministicGateway} 的批次约定一致）；未声明时，消费者以“当前所有待处理提交中
 * 最小的 seq”自动锚定，之后再提交更小的 seq 会被引擎按不连续快速失败（future 异常）。
 *
 * <p>采集得到的 {@link Snapshot} 是全新构建的不可变数据结构，可在写入继续时安全地异步序列化。
 */
public final class StreamingGateway implements AutoCloseable {

    private sealed interface Task permits CommandTask, GroupTask, ExportTask, StopTask {
    }

    private record CommandTask(Command command, CompletableFuture<CommandResult> future)
            implements Task {
    }

    private record GroupTask(long firstSeq, long lastSeq,
                             List<Command> group, CompletableFuture<BatchResult> future)
            implements Task {
    }

    private record ExportTask(CompletableFuture<SnapshotExport> future) implements Task {
    }

    private record StopTask() implements Task {
    }

    private final MatchingEngine engine;
    private final ArrayBlockingQueue<Task> queue = new ArrayBlockingQueue<>(1024 * 1024);

    /** 缺号缓冲：单条指令（仅消费者线程访问，无需加锁）。 */
    private final TreeMap<Long, CommandTask> pendingSingles = new TreeMap<>();
    /** 缺号缓冲：原子委托组，按组首 seq 排序（组首衔接水位时整体执行）。 */
    private final TreeMap<Long, GroupTask> pendingGroups = new TreeMap<>();

    /** 屏障排空后需要重新塞回队首的非指令任务（并发导出/关闭），保持 FIFO。 */
    private final java.util.ArrayDeque<Task> frontDeque = new java.util.ArrayDeque<>();

    private final Thread consumer;
    private volatile boolean stopped;

    /** JSON 序列化独占线程：慢 IO 完全不占用撮合消费者。 */
    private final ExecutorService jsonWorker =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "snapshot-json");
                t.setDaemon(true);
                return t;
            });

    public StreamingGateway(MatchingEngine engine) {
        this.engine = engine;
        this.consumer = new Thread(this::pump, "streaming-consumer");
        this.consumer.setDaemon(true);
        this.consumer.start();
    }

    /**
     * 显式声明全序起点：第一条真实提交的 seq 必须等于 {@code startSeq}。
     * 开放并发提交前调用可避免“谁先到谁锚定”的歧义。
     */
    public void prime(long startSeq) {
        engine.prime(startSeq);
    }

    /**
     * 并发/乱序提交一条指令。乱序时按 seq 缓冲，缺号补齐后自动连续应用；
     * seq 不大于水位、与已缓冲指令或待处理委托组的 seq 冲突时以 DUPLICATE_COMMAND 完成。
     */
    public CompletableFuture<CommandResult> submit(Command command) {
        CompletableFuture<CommandResult> future = new CompletableFuture<>();
        if (stopped) {
            future.completeExceptionally(new IllegalStateException("网关已关闭"));
            return future;
        }
        try {
            queue.put(new CommandTask(command, future));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * 原子委托组：组内在全序消费者上整体应用，不会插入任何其他指令；
     * 起点 seq 未衔接水位时整组等待，衔接的那一刻一次性执行（成功整体落地 / 任一被拒整体回滚）。
     */
    public CompletableFuture<BatchResult> submitAtomicGroup(List<Command> group) {
        CompletableFuture<BatchResult> future = new CompletableFuture<>();
        if (stopped) {
            future.completeExceptionally(new IllegalStateException("网关已关闭"));
            return future;
        }
        List<Command> copy = List.copyOf(group);
        long first = copy.stream().mapToLong(Command::getSeq).min().orElse(Long.MIN_VALUE);
        long last = copy.stream().mapToLong(Command::getSeq).max().orElse(Long.MIN_VALUE);
        try {
            queue.put(new GroupTask(first, last, copy, future));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * 同步导出一致性快照：导出点 = 本调用之前已成功入队的全部提交之后、之后的提交之前。
     * 阻塞调用方直到 JSON 序列化完成，但<b>不阻塞其他账户的写入</b>。
     */
    public SnapshotExport exportSnapshot() {
        try {
            return exportSnapshotAsync().get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("一致性快照导出失败", e);
        }
    }

    /** 异步导出：调用线程立即返回；导出在全序队列排到屏障点后执行。 */
    public CompletableFuture<SnapshotExport> exportSnapshotAsync() {
        CompletableFuture<SnapshotExport> future = new CompletableFuture<>();
        if (stopped) {
            future.completeExceptionally(new IllegalStateException("网关已关闭"));
            return future;
        }
        try {
            queue.put(new ExportTask(future));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.completeExceptionally(e);
        }
        return future;
    }

    // ---------------- 消费者 ----------------

    private Task pollNext() throws InterruptedException {
        Task front = frontDeque.pollFirst();
        return front != null ? front : queue.take();
    }

    private void pump() {
        try {
            while (true) {
                Task task = pollNext();
                if (task instanceof StopTask) {
                    return;
                } else if (task instanceof CommandTask ct) {
                    accept(ct);
                } else if (task instanceof GroupTask gt) {
                    acceptGroup(gt);
                } else if (task instanceof ExportTask et) {
                    runExportBarrier(et);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void accept(CommandTask task) {
        Command c = task.command();
        long seq = c.getSeq();
        if (seq <= engine.getLastSeq() || pendingSingles.containsKey(seq)) {
            task.future().complete(CommandResult.rejected(new Rejection(
                    seq, c.getClientOrderId(), RejectReason.DUPLICATE_COMMAND,
                    "seq=" + seq + " 已应用或已在缓冲中（重复提交）")));
            return;
        }
        for (GroupTask g : pendingGroups.values()) {
            if (seq >= g.firstSeq() && seq <= g.lastSeq()) {
                task.future().complete(CommandResult.rejected(new Rejection(
                        seq, c.getClientOrderId(), RejectReason.DUPLICATE_COMMAND,
                        "seq=" + seq + " 已包含在待处理委托组 ["
                                + g.firstSeq() + ".." + g.lastSeq() + "] 中")));
                return;
            }
        }
        pendingSingles.put(seq, task);
        pumpPending();
    }

    private void acceptGroup(GroupTask task) {
        // 静态区间冲突：与已缓冲单条指令或其他待处理组重叠，直接整组拒绝（不等待、不执行）
        for (long seq = task.firstSeq(); seq <= task.lastSeq(); seq++) {
            if (seq <= engine.getLastSeq() || pendingSingles.containsKey(seq)) {
                task.future().complete(BatchResult.rolledBack(task.firstSeq(),
                        new Rejection(task.firstSeq(), null, RejectReason.BATCH_REJECTED,
                                "委托组区间 [" + task.firstSeq() + ".." + task.lastSeq()
                                        + "] 与已应用/已缓冲指令 seq=" + seq + " 冲突"),
                        null));
                return;
            }
        }
        for (GroupTask other : pendingGroups.values()) {
            if (task.firstSeq() <= other.lastSeq() && other.firstSeq() <= task.lastSeq()) {
                task.future().complete(BatchResult.rolledBack(task.firstSeq(),
                        new Rejection(task.firstSeq(), null, RejectReason.BATCH_REJECTED,
                                "委托组区间 [" + task.firstSeq() + ".." + task.lastSeq()
                                        + "] 与待处理委托组 [" + other.firstSeq() + ".."
                                        + other.lastSeq() + "] 重叠"),
                        null));
                return;
            }
        }
        pendingGroups.put(task.firstSeq(), task);
        pumpPending();
    }

    /**
     * 统一推进：锚定起点后，只要“下一条 seq”对应一条已缓冲单指令或一个起点匹配的原子组，
     * 就交给引擎；遇到缺号停止，等待乱序提交补齐。
     */
    private void pumpPending() {
        if (engine.getLastSeq() == -1L
                && (!pendingSingles.isEmpty() || !pendingGroups.isEmpty())) {
            long anchor = Long.MAX_VALUE;
            if (!pendingSingles.isEmpty()) {
                anchor = Math.min(anchor, pendingSingles.firstKey());
            }
            if (!pendingGroups.isEmpty()) {
                anchor = Math.min(anchor, pendingGroups.firstKey());
            }
            engine.prime(anchor);
        }
        while (true) {
            long expected = engine.getLastSeq() + 1;
            CommandTask single = pendingSingles.get(expected);
            if (single != null) {
                pendingSingles.remove(expected);
                try {
                    single.future().complete(engine.apply(single.command()));
                } catch (Throwable t) {
                    single.future().completeExceptionally(t);
                }
                continue;
            }
            GroupTask group = pendingGroups.get(expected);
            if (group != null) {
                pendingGroups.remove(expected);
                try {
                    group.future().complete(engine.applyAtomicBatch(group.group()));
                } catch (Throwable t) {
                    group.future().completeExceptionally(t);
                }
                continue;
            }
            return; // 缺号：等待乱序提交补齐
        }
    }

    /**
     * 导出屏障：排空此导出请求入队时已经排在队列里的全部任务（即“此前已提交”的工作），
     * 其中单条/委托组全部缓冲并尽可能推进，其他导出/关闭请求保持 FIFO 顺延，
     * 然后在明确指令边界采集快照。
     */
    private void runExportBarrier(ExportTask export) {
        List<Task> drained = new ArrayList<>();
        queue.drainTo(drained);
        List<Task> deferred = new ArrayList<>();
        for (Task t : drained) {
            if (t instanceof CommandTask ct) {
                accept(ct);
            } else if (t instanceof GroupTask gt) {
                acceptGroup(gt);
            } else {
                // 并发的其他导出 / 关闭请求必须排在本次导出之后
                deferred.add(t);
            }
        }
        for (int i = deferred.size() - 1; i >= 0; i--) {
            frontDeque.addFirst(deferred.get(i));
        }
        // 若仍有待处理提交因缺号卡住（调用方跳号），快照点取“已连续应用到的水位”，
        // 缺号提交会在补齐后继续；这不影响快照边界本身的明确性。

        long boundary = engine.getLastSeq();
        long startNanos = System.nanoTime();
        Snapshot snapshot = engine.snapshot(); // 持 guard 纯内存采集，构建不可变副本
        long captureNanos = System.nanoTime() - startNanos;

        // JSON 序列化移到锁外独占线程：写入立即继续，不等待导出 IO
        jsonWorker.submit(() -> {
            try {
                String json = JsonIO.toJson(snapshot);
                export.future().complete(
                        new SnapshotExport(boundary, snapshot, json, captureNanos));
            } catch (Throwable t) {
                export.future().completeExceptionally(t);
            }
        });
    }

    @Override
    public void close() {
        if (stopped) {
            return;
        }
        stopped = true;
        try {
            queue.put(new StopTask());
            consumer.join(30_000);
            jsonWorker.shutdown();
            if (!jsonWorker.awaitTermination(30, TimeUnit.SECONDS)) {
                jsonWorker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
