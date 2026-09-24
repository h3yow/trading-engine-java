package com.github.h3yow.tradingenginejava.matching.gateway;

import com.github.h3yow.tradingenginejava.matching.engine.MatchingEngine;
import com.github.h3yow.tradingenginejava.matching.model.Command;
import com.github.h3yow.tradingenginejava.matching.model.CommandResult;
import com.github.h3yow.tradingenginejava.matching.model.RejectReason;
import com.github.h3yow.tradingenginejava.matching.model.Rejection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 并发提交网关。
 *
 * <p>多账户可从任意线程、以任意调度顺序并发提交同一批指令。工作线程把指令投入
 * {@code seq -> CompletableFuture} 后阻塞等待；一个<b>唯一的撮合消费者</b>严格按 seq
 * 升序调用 {@link MatchingEngine#apply(Command)} 并兑现对应 future。因此无论提交线程的
 * 调度顺序如何变化，引擎始终看到同一条全序：
 * <ul>
 *   <li>成交序列（价格、量、成交编号）完全一致；</li>
 *   <li>各账户现金/持仓四个分量完全一致；</li>
 *   <li>拒绝集合与原因完全一致。</li>
 * </ul>
 * 同批内重复 seq 以 DUPLICATE_COMMAND 明确拒绝；超限、资金/持仓不足等也均为显式拒绝，
 * 绝不静默丢弃。
 */
public final class DeterministicGateway {

    private final MatchingEngine engine;

    public DeterministicGateway(MatchingEngine engine) {
        this.engine = engine;
    }

    /**
     * 乱序并发提交一整批指令，返回 seq -&gt; 结果（按 seq 升序）。
     *
     * @param shuffleSeed 非 null 时按该种子打乱线程派发顺序，主动制造不同并发到达时序；
     *                    传入不同种子验证结果不变。
     */
    public Map<Long, CommandResult> submitBatch(Collection<Command> batch, Long shuffleSeed) {
        List<Command> ordered = new ArrayList<>(batch);
        ordered.sort(Comparator.comparingLong(Command::getSeq));

        TreeMap<Long, CommandResult> localRejects = new TreeMap<>();
        List<Command> unique = new ArrayList<>();
        Long prev = null;
        for (Command c : ordered) {
            if (prev != null && c.getSeq() == prev) {
                localRejects.put(c.getSeq(), CommandResult.rejected(new Rejection(
                        c.getSeq(), c.getClientOrderId(),
                        RejectReason.DUPLICATE_COMMAND,
                        "同一批次中存在重复 seq=" + c.getSeq())));
            } else {
                unique.add(c);
            }
            prev = c.getSeq();
        }
        // 消费者按 seq 升序应用；unique 已按 seq 排好序（ordered 排序后保留首条）
        List<Command> applyOrder = new ArrayList<>(unique);
        if (shuffleSeed != null) {
            java.util.Collections.shuffle(unique, new Random(shuffleSeed));
        }
        if (unique.isEmpty()) {
            return localRejects;
        }
        engine.prime(unique.stream().mapToLong(Command::getSeq).min().orElseThrow());

        ConcurrentHashMap<Long, CompletableFuture<CommandResult>> futures = new ConcurrentHashMap<>();
        CountDownLatch ready = new CountDownLatch(unique.size());
        CountDownLatch start = new CountDownLatch(1);

        // 唯一撮合消费者：严格按 seq 升序串行应用，与工作线程何时到达无关
        Thread consumer = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            for (Command c : applyOrder) {
                try {
                    CommandResult result = engine.apply(c);
                    futures.get(c.getSeq()).complete(result);
                } catch (Throwable t) {
                    futures.get(c.getSeq()).completeExceptionally(t);
                }
            }
        }, "matching-consumer");
        consumer.setDaemon(true);
        consumer.start();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
            for (Command c : unique) {
                CompletableFuture<CommandResult> f = new CompletableFuture<>();
                futures.put(c.getSeq(), f);
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        // 实际进入顺序由 JVM 调度决定；提交线程只等待全序消费者兑现结果
                        f.get(30, TimeUnit.SECONDS);
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                });
            }
            ready.await();
            start.countDown();
            consumer.join(30_000);
            if (consumer.isAlive()) {
                throw new IllegalStateException("撮合消费者执行超时（seq 不连续或发生死锁）");
            }
            if (!errors.isEmpty()) {
                throw new RuntimeException("并发提交发生异常: " + errors.get(0), errors.get(0));
            }

            TreeMap<Long, CommandResult> results = new TreeMap<>();
            for (Command c : unique) {
                try {
                    results.put(c.getSeq(), futures.get(c.getSeq()).get(1, TimeUnit.SECONDS));
                } catch (Exception e) {
                    throw new RuntimeException("回收 seq=" + c.getSeq() + " 结果失败", e);
                }
            }
            localRejects.forEach(results::putIfAbsent);
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /** 顺序派发（不打乱），仍走多线程并发提交。 */
    public Map<Long, CommandResult> submitBatch(Collection<Command> batch) {
        return submitBatch(batch, null);
    }
}
