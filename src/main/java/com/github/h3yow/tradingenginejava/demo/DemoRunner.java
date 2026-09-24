package com.github.h3yow.tradingenginejava.demo;

import com.github.h3yow.tradingenginejava.command.Command;
import com.github.h3yow.tradingenginejava.command.CommandResult;
import com.github.h3yow.tradingenginejava.engine.EngineSnapshot;
import com.github.h3yow.tradingenginejava.engine.MatchingEngine;
import com.github.h3yow.tradingenginejava.engine.ReplayEngine;
import com.github.h3yow.tradingenginejava.model.Order;
import com.github.h3yow.tradingenginejava.model.OrderType;
import com.github.h3yow.tradingenginejava.model.RejectReason;
import com.github.h3yow.tradingenginejava.model.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 本地端到端演示：mvn spring-boot:run -Dspring-boot.run.profiles=demo
 *
 * 打印：输入命令、每条命令的接受/拒绝判定依据、成交序列、最终各账户余额，
 * 以及“并发收集提交 / 重复回放 / 快照恢复”三条确定性路径的等价性结论。
 */
@Component
@Profile("demo")
public final class DemoRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoRunner.class);

    @Override
    public void run(ApplicationArguments args) {
        String sym = "BTC";
        List<Command> commands = new ArrayList<>(List.of(
                Command.deposit(1, "alice", 100_000L, sym, 0L),
                Command.deposit(2, "bob", 0L, sym, 1_000L),
                Command.deposit(3, "carol", 100_000L, sym, 1_000L),
                // bob 在 101 挂 300 卖；carol 在 100 挂 200 卖
                Command.place(4, "bob", "ask-1", sym, Side.SELL, OrderType.LIMIT, 101, 300),
                Command.place(5, "carol", "ask-2", sym, Side.SELL, OrderType.LIMIT, 100, 200),
                // alice 限价买 400@101：先吃 carol 200@100，再吃 bob 200@101，剩 0？400=200+200
                Command.place(6, "alice", "bid-1", sym, Side.BUY, OrderType.LIMIT, 101, 400),
                // bob 撤剩余 ask-1 的 100
                Command.cancel(7, "bob", "ask-1"),
                // 故意触发各类拒绝：非法数量 / 未知账户 / 现金不足 / 自成交 / 无流动性市价
                Command.place(8, "alice", "bad-qty", sym, Side.BUY, OrderType.LIMIT, 100, 0),
                Command.place(9, "ghost", "ghost-order", sym, Side.BUY, OrderType.MARKET, 0, 10),
                Command.place(10, "alice", "too-big", sym, Side.BUY, OrderType.LIMIT, 100, 100_000),
                Command.place(11, "carol", "freeze-1", sym, Side.SELL, OrderType.LIMIT, 1, 1) // 低价挂卖，演示持仓冻结
        ));

        log.info("================ 输入命令（{} 条）================", commands.size());
        commands.forEach(c -> log.info("  seq={} {}", c.seq(), describe(c)));

        // 路径1：顺序执行
        MatchingEngine engine = new MatchingEngine();
        List<CommandResult> results = engine.submitBatch(commands);

        log.info("================ 判定结果 ================");
        for (CommandResult r : results) {
            if (r.accepted()) {
                log.info("seq={} ACCEPTED trades={} | {}", r.seq(), r.tradeSeqs().length, r.message());
            } else {
                log.info("seq={} REJECTED reason={} | basis={}", r.seq(), r.reason(), r.message());
            }
        }

        log.info("================ 成交序列（{} 笔）================", engine.getTrades().size());
        engine.getTrades().forEach(t -> log.info("  trade#{} seq={} {} {}@{} qty={} taker={} maker={}",
                t.tradeSeq(), t.commandSeq(), t.symbol(),
                t.takerSide(), t.price(), t.qty(), t.takerOrderId(), t.makerOrderId()));

        EngineSnapshot snapshot = engine.snapshot();
        log.info("================ 最终账户 ================");
        snapshot.accounts().forEach(a -> log.info("  {} cash={}(reserved={}) positions={} reservedPos={}",
                a.accountId(), a.cash(), a.reservedCash(), a.positions(), a.reservedPositions()));
        log.info("================ 订单状态 ================");
        snapshot.allOrders().values().stream()
                .filter(o -> List.of(com.github.h3yow.tradingenginejava.model.OrderStatus.FILLED,
                        com.github.h3yow.tradingenginejava.model.OrderStatus.CANCELLED,
                        com.github.h3yow.tradingenginejava.model.OrderStatus.PARTIALLY_FILLED).contains(o.getStatus()))
                .forEach(o -> log.info("  {} {} filled={}/{} status={} reason={}",
                        o.getOrderId(), o.getSide(), o.getFilledQty(), o.getOriginalQty(),
                        o.getStatus(), o.getRejectReason()));

        // 路径2：并发收集器（模拟 4 线程乱序提交同一批）
        MatchingEngine concurrent = new MatchingEngine();
        MatchingEngine.Collector collector = concurrent.newCollector();
        List<Command> shuffled = new ArrayList<>(commands);
        java.util.Collections.shuffle(shuffled);
        int threads = 4;
        int chunk = (shuffled.size() + threads - 1) / threads;
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            List<Command> part = shuffled.subList(Math.min(t * chunk, shuffled.size()),
                    Math.min((t + 1) * chunk, shuffled.size()));
            Thread th = new Thread(() -> part.forEach(collector::add));
            workers.add(th);
            th.start();
        }
        workers.forEach(w -> {
            try {
                w.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        collector.finish();

        // 路径3：从快照恢复后续跑 + 重复回放
        MatchingEngine restored = MatchingEngine.restore(snapshot);
        List<Command> more = List.of(
                Command.deposit(12, "dave", 50_000L, sym, 0L),
                Command.place(13, "dave", "dave-buy", sym, Side.BUY, OrderType.MARKET, 0, 50),
                Command.place(14, "alice", "alice-mid", sym, Side.BUY, OrderType.LIMIT, 105, 10)
        );
        List<CommandResult> continued = restored.submitBatch(more);

        List<Command> all = new ArrayList<>(commands);
        all.addAll(more);
        ReplayEngine.ReplayOutcome replay1 = ReplayEngine.replay(all);
        ReplayEngine.ReplayOutcome replay2 = ReplayEngine.replay(all);

        log.info("================ 确定性验证 ================");
        boolean concurrencySame = fp(snapshot).equals(fp(concurrent.snapshot()));
        log.info("1) 4线程乱序并发提交 vs 顺序执行 状态指纹一致: {}", concurrencySame);
        boolean replaySame = fp(replay1.snapshot()).equals(fp(replay2.snapshot()));
        log.info("2) 同一命令日志重复回放 状态指纹一致: {}", replaySame);
        EngineSnapshot continuedSnap = restored.snapshot();
        boolean restoreSame = fp(replay1.snapshot()).equals(fp(continuedSnap));
        log.info("3) 快照恢复后续跑 vs 一次性完整回放 状态指纹一致: {}", restoreSame);
        boolean noDupTrades = continuedSnap.trades().stream().map(t -> t.tradeSeq()).collect(Collectors.toSet())
                .size() == continuedSnap.trades().size();
        log.info("4) 恢复路径无重复成交: {} (总成交={})", noDupTrades, continuedSnap.trades().size());
        log.info("5) 续跑新命令接受数: {}/{}, 拒绝原因集合: {}",
                continued.stream().filter(CommandResult::accepted).count(), more.size(),
                results.stream().filter(r -> !r.accepted()).map(CommandResult::reason)
                        .collect(Collectors.toCollection(java.util.TreeSet::new)));

        boolean allOk = concurrencySame && replaySame && restoreSame && noDupTrades;
        log.info("================ 结论: {} ================",
                allOk ? "所有路径成交序列与余额一致，撮合结果确定且可解释 ✅" : "存在不一致 ❌");
    }

    private String describe(Command c) {
        return switch (c.type()) {
            case DEPOSIT -> "DEPOSIT " + c.accountId() + " cash=" + c.depositCash()
                    + " " + c.symbol() + "=" + c.depositQty();
            case PLACE -> "PLACE " + c.orderType() + " " + c.side() + " " + c.symbol()
                    + " qty=" + c.qty() + " price=" + c.price()
                    + " by " + c.accountId() + " id=" + c.orderId();
            case CANCEL -> "CANCEL " + c.orderId() + " by " + c.accountId();
            case MODIFY -> "MODIFY " + c.orderId() + " qty=" + c.qty() + " price=" + c.price();
        };
    }

    private String fp(EngineSnapshot s) {
        StringBuilder sb = new StringBuilder();
        s.accounts().forEach(a -> sb.append(a.accountId()).append(a.cash()).append(a.reservedCash())
                .append(a.positions()).append(a.reservedPositions()));
        s.trades().forEach(t -> sb.append(t.tradeSeq()).append(t.makerOrderId())
                .append(t.takerOrderId()).append(t.price()).append(t.qty()));
        s.allOrders().values().forEach(o -> sb.append(o.getOrderId()).append(o.getStatus())
                .append(o.getFilledQty()).append(o.getRemainingQty()));
        sb.append(s.nextSeq()).append(s.nextTradeSeq());
        return Integer.toHexString(sb.toString().hashCode());
    }
}
