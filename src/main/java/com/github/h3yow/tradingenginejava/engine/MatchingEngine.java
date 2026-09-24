package com.github.h3yow.tradingenginejava.engine;

import com.github.h3yow.tradingenginejava.account.Account;
import com.github.h3yow.tradingenginejava.command.Command;
import com.github.h3yow.tradingenginejava.command.CommandResult;
import com.github.h3yow.tradingenginejava.model.Order;
import com.github.h3yow.tradingenginejava.model.OrderStatus;
import com.github.h3yow.tradingenginejava.model.OrderType;
import com.github.h3yow.tradingenginejava.model.RejectReason;
import com.github.h3yow.tradingenginejava.model.Side;
import com.github.h3yow.tradingenginejava.model.Trade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 撮合引擎：内存订单簿 + 多账户账本。
 *
 * 确定性保证：
 *  1. 所有状态变更都在 this 单一锁内串行完成，撮合过程对外部不可见中间态；
 *  2. 命令携带单调递增的 seq，submitBatch 先按 seq 升序排序、去重，再逐条执行；
 *     因此多线程并发提交同一批命令时，无论线程调度顺序如何，成交序列与余额完全一致；
 *  3. 成交序号（tradeSeq）由引擎单调分配，快照保存 nextTradeSeq，恢复后续号不重用，
 *     重复回放 / 快照恢复不会产生重复成交。
 *
 * 自成交：吃单前预检，若任一可成交档位存在同账户对手单，整单拒绝（SELF_TRADE）。
 */
public final class MatchingEngine {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngine.class);

    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final Map<String, OrderBook> books = new LinkedHashMap<>();
    private final Map<String, Order> allOrders = new LinkedHashMap<>();
    private final List<Trade> trades = new ArrayList<>();

    private long lastSeq = 0L;
    private long nextTradeSeq = 1L;

    /** 每个活动订单当前实际冻结的现金（限价买）/ 持仓（卖），按 orderId 精确记账。 */
    private final Map<String, Long> reservedCashByOrder = new LinkedHashMap<>();
    private final Map<String, Long> reservedQtyByOrder = new LinkedHashMap<>();

    public MatchingEngine() {
    }

    /** 从快照恢复（恢复后可继续撮合，历史成交已在 trades 中，不会被重复执行）。 */
    public static synchronized MatchingEngine restore(EngineSnapshot snapshot) {
        MatchingEngine engine = new MatchingEngine();
        engine.lastSeq = snapshot.nextSeq() - 1;
        engine.nextTradeSeq = snapshot.nextTradeSeq();
        for (EngineSnapshot.AccountSnapshot a : snapshot.accounts()) {
            Account account = new Account(a.accountId());
            account.deposit(a.cash(), null, 0L);
            for (Map.Entry<String, Long> e : a.positions().entrySet()) {
                account.deposit(0L, e.getKey(), e.getValue());
            }
            // 恢复冻结额：通过 reserve 系列接口记账（可用余额必须覆盖冻结额）
            account.reserveCash(a.reservedCash());
            for (Map.Entry<String, Long> e : a.reservedPositions().entrySet()) {
                account.reservePosition(e.getKey(), e.getValue());
            }
            engine.accounts.put(a.accountId(), account);
        }
        for (Order o : snapshot.allOrders().values()) {
            engine.allOrders.put(o.getOrderId(), copyOf(o));
        }
        for (Order o : snapshot.activeOrders()) {
            Order copy = engine.allOrders.get(o.getOrderId());
            engine.bookFor(o.getSymbol()).add(copy);
            // 活动订单当前冻结量 = 剩余量（卖单冻结持仓；限价买单按限价冻结现金）。
            long remaining = copy.getRemainingQty();
            if (copy.getSide() == Side.BUY && copy.getType() == OrderType.LIMIT) {
                engine.reservedCashByOrder.put(copy.getOrderId(), copy.getPrice() * remaining);
            } else if (copy.getSide() == Side.SELL) {
                engine.reservedQtyByOrder.put(copy.getOrderId(), remaining);
            }
        }
        engine.trades.addAll(snapshot.trades());
        return engine;
    }

    public synchronized Account ensureAccount(String accountId) {
        return accounts.computeIfAbsent(accountId, Account::new);
    }

    /** 串行执行单条命令；seq 必须严格大于已执行的 seq。 */
    public synchronized CommandResult submit(Command command) {
        return execute(command);
    }

    /**
     * 提交一批命令：先按 seq 升序排序，再在单一锁内逐条执行。
     * 同批出现重复 seq 时，只有第一条参与执行，其余以 INVALID_SEQUENCE 明确拒绝。
     */
    public synchronized List<CommandResult> submitBatch(List<Command> commands) {
        List<Command> sorted = new ArrayList<>(commands);
        sorted.sort(Comparator.comparingLong(Command::seq));
        List<CommandResult> results = new ArrayList<>(sorted.size());
        java.util.Set<Long> seenInBatch = new java.util.HashSet<>();
        for (Command c : sorted) {
            if (!seenInBatch.add(c.seq())) {
                results.add(CommandResult.reject(c.seq(), RejectReason.INVALID_SEQUENCE,
                        "duplicate seq " + c.seq() + " within the same batch; only the first command is executed"));
                continue;
            }
            results.add(execute(c));
        }
        return results;
    }

    private CommandResult execute(Command c) {
        if (c.seq() <= lastSeq) {
            return rejected(c, RejectReason.INVALID_SEQUENCE,
                    "seq=" + c.seq() + " must be greater than last executed seq=" + lastSeq);
        }
        CommandResult result;
        try {
            result = switch (c.type()) {
                case DEPOSIT -> doDeposit(c);
                case PLACE -> doPlace(c);
                case CANCEL -> doCancel(c);
                case MODIFY -> doModify(c);
            };
        } catch (RuntimeException e) {
            log.warn("command {} failed unexpectedly: {}", c.seq(), e.toString());
            throw e;
        }
        lastSeq = c.seq();
        if (!result.accepted()) {
            log.info("INPUT seq={} {} | REJECTED reason={} basis={}",
                    c.seq(), brief(c), result.reason(), result.message());
        } else {
            log.info("INPUT seq={} {} | ACCEPTED trades={} detail={}",
                    c.seq(), brief(c), result.tradeSeqs().length, result.message());
        }
        return result;
    }

    private static String brief(Command c) {
        return switch (c.type()) {
            case DEPOSIT -> "DEPOSIT account=" + c.accountId() + " cash=" + c.depositCash()
                    + " " + c.symbol() + "=" + c.depositQty();
            case PLACE -> "PLACE " + c.orderType() + " " + c.side() + " " + c.symbol()
                    + " qty=" + c.qty() + " price=" + c.price()
                    + " account=" + c.accountId() + " id=" + c.orderId();
            case CANCEL -> "CANCEL id=" + c.orderId() + " account=" + c.accountId();
            case MODIFY -> "MODIFY id=" + c.orderId() + " qty=" + c.qty() + " price=" + c.price()
                    + " account=" + c.accountId();
        };
    }

    private CommandResult rejected(Command c, RejectReason reason, String basis) {
        return CommandResult.reject(c.seq(), reason, basis);
    }

    /* ---------------- DEPOSIT ---------------- */

    private CommandResult doDeposit(Command c) {
        if (c.accountId() == null || c.accountId().isBlank()) {
            return rejected(c, RejectReason.UNKNOWN_ACCOUNT, "deposit requires a non-blank accountId");
        }
        if (c.depositCash() < 0 || c.depositQty() < 0
                || (c.depositCash() == 0 && c.depositQty() == 0)) {
            return rejected(c, RejectReason.INVALID_QUANTITY,
                    "deposit amount must be positive: cash=" + c.depositCash() + ", qty=" + c.depositQty());
        }
        Account account = ensureAccount(c.accountId());
        account.deposit(c.depositCash(), c.symbol(), c.depositQty());
        return CommandResult.ok(c.seq(), new long[0],
                "deposited cash=" + c.depositCash() + " " + c.symbol() + " qty=" + c.depositQty()
                        + " -> cash=" + account.getCash()
                        + ", available=" + account.getAvailablePosition(safeSymbol(c.symbol())));
    }

    /* ---------------- PLACE ---------------- */

    private CommandResult doPlace(Command c) {
        CommandResult structural = validatePlaceShape(c);
        if (structural != null) {
            return structural;
        }
        Account account = accounts.get(c.accountId());
        if (account == null) {
            return rejected(c, RejectReason.UNKNOWN_ACCOUNT,
                    "account " + c.accountId() + " does not exist; deposit before placing orders");
        }
        if (allOrders.containsKey(c.orderId())) {
            return rejected(c, RejectReason.DUPLICATE_ORDER_ID,
                    "orderId " + c.orderId() + " already exists");
        }
        OrderBook book = bookFor(c.symbol());
        boolean market = c.orderType() == OrderType.MARKET;

        // 市价单若无任何对手流动性，明确拒绝而非静默挂住。
        if (market && noCounterLiquidity(book, c.side())) {
            return rejected(c, RejectReason.NO_LIQUIDITY,
                    "market " + c.side() + " has no opposite resting order to match");
        }
        // 自成交预检：任何可成交档位存在同账户对手单即整单拒绝。
        if (book.hasCounterOrderOfAccount(c.side(), c.price(), market, c.accountId())) {
            return rejected(c, RejectReason.SELF_TRADE,
                    "account " + c.accountId() + " would cross its own resting "
                            + (c.side() == Side.BUY ? "ASK" : "BID") + "; whole order rejected before matching");
        }

        // 限额校验与冻结。
        if (c.side() == Side.BUY) {
            if (!market) {
                long need = c.price() * c.qty();
                if (!account.reserveCash(need)) {
                    return rejected(c, RejectReason.INSUFFICIENT_CASH,
                            "limit buy needs " + need + " reserved cash but available=" + account.getAvailableCash());
                }
                reservedCashByOrder.put(c.orderId(), need);
            }
        } else {
            if (!account.reservePosition(c.symbol(), c.qty())) {
                return rejected(c, RejectReason.INSUFFICIENT_POSITION,
                        "sell needs " + c.qty() + " available " + c.symbol()
                                + " but available=" + account.getAvailablePosition(c.symbol()));
            }
            reservedQtyByOrder.put(c.orderId(), c.qty());
        }

        Order order = new Order(c.orderId(), c.accountId(), c.symbol(), c.side(), c.orderType(),
                market ? 0L : c.price(), c.qty(), c.seq());
        allOrders.put(order.getOrderId(), order);
        MatchOutcome outcome = match(order, c.symbol());

        String message = describeOutcome(order, outcome);
        if (order.getStatus() == OrderStatus.NEW || order.getStatus() == OrderStatus.PARTIALLY_FILLED) {
            if (market) {
                // 市价单剩余量不挂簿：按停止原因明确标记，并释放剩余冻结。
                order.setStatus(OrderStatus.CANCELLED);
                order.setRejectReason(outcome.stopReason);
                releaseResidualReservation(account, order, c.symbol());
                message += "; MARKET remainder cancelled reason=" + outcome.stopReason;
            } else {
                // 限价剩余单挂簿：剩余冻结必须保留（consumeReservation 已逐笔扣减）。
                book.add(order);
            }
        }
        return CommandResult.ok(c.seq(), tradeSeqsOf(outcome), message);
    }

    private CommandResult validatePlaceShape(Command c) {
        if (c.accountId() == null || c.accountId().isBlank()) {
            return rejected(c, RejectReason.UNKNOWN_ACCOUNT, "place requires accountId");
        }
        if (c.orderId() == null || c.orderId().isBlank() || c.symbol() == null || c.symbol().isBlank()) {
            return rejected(c, RejectReason.INVALID_QUANTITY,
                    "place requires non-blank orderId and symbol");
        }
        if (c.side() == null || c.orderType() == null) {
            return rejected(c, RejectReason.INVALID_QUANTITY, "place requires side and orderType");
        }
        if (c.qty() <= 0) {
            return rejected(c, RejectReason.INVALID_QUANTITY,
                    "qty must be positive but was " + c.qty());
        }
        if (c.orderType() == OrderType.LIMIT && c.price() <= 0) {
            return rejected(c, RejectReason.INVALID_PRICE,
                    "limit price must be positive but was " + c.price());
        }
        return null;
    }

    /* ---------------- CANCEL ---------------- */

    private CommandResult doCancel(Command c) {
        Order order = allOrders.get(c.orderId());
        if (order == null || !order.getAccountId().equals(c.accountId())) {
            return rejected(c, RejectReason.UNKNOWN_ORDER,
                    "no active/known order " + c.orderId() + " for account " + c.accountId());
        }
        if (order.getStatus() != OrderStatus.NEW && order.getStatus() != OrderStatus.PARTIALLY_FILLED) {
            return rejected(c, RejectReason.ORDER_NOT_ACTIVE,
                    "order " + c.orderId() + " is " + order.getStatus() + " and cannot be cancelled");
        }
        // 活动剩余单一定在簿中；先取出 symbol 用于持仓解冻。
        String symbol = symbolOfActive(order);
        OrderBook book = books.get(symbol);
        if (book != null) {
            book.remove(order);
        }
        Account account = accounts.get(order.getAccountId());
        long released = releaseResidualReservation(account, order, symbol);
        order.setStatus(OrderStatus.CANCELLED);
        return CommandResult.ok(c.seq(), new long[0],
                "cancelled " + c.orderId() + " remaining=" + order.getRemainingQty()
                        + " released=" + released);
    }

    /* ---------------- MODIFY ---------------- */

    private CommandResult doModify(Command c) {
        Order order = allOrders.get(c.orderId());
        if (order == null || !order.getAccountId().equals(c.accountId())) {
            return rejected(c, RejectReason.UNKNOWN_ORDER,
                    "no known order " + c.orderId() + " for account " + c.accountId());
        }
        if (order.getStatus() != OrderStatus.NEW && order.getStatus() != OrderStatus.PARTIALLY_FILLED) {
            return rejected(c, RejectReason.ORDER_NOT_ACTIVE,
                    "order " + c.orderId() + " is " + order.getStatus() + " and cannot be modified");
        }
        if (c.orderType() == null) {
            return rejected(c, RejectReason.INVALID_QUANTITY, "modify requires orderType");
        }
        if (c.qty() <= 0) {
            return rejected(c, RejectReason.INVALID_QUANTITY,
                    "new total qty must be positive but was " + c.qty());
        }
        if (c.qty() <= order.getFilledQty()) {
            return rejected(c, RejectReason.INVALID_QUANTITY,
                    "new total qty " + c.qty() + " must exceed filled qty " + order.getFilledQty());
        }
        boolean newMarket = c.orderType() == OrderType.MARKET;
        long newPrice = newMarket ? 0L : c.price();
        if (!newMarket && newPrice <= 0) {
            return rejected(c, RejectReason.INVALID_PRICE,
                    "limit price must be positive but was " + c.price());
        }

        String symbol = symbolOfActive(order);
        OrderBook book = bookFor(symbol);
        Account account = accounts.get(order.getAccountId());
        book.remove(order);

        // 先按旧条款释放未成交部分的冻结。
        releaseResidualReservation(account, order, symbol);

        // 改单后以新条款重新做自成交预检与限额校验。
        if (newMarket && noCounterLiquidity(book, order.getSide())) {
            // 恢复旧条款挂单，拒绝本次修改（保持状态自洽）。
            book.add(order);
            reReserveOldTerms(account, order, symbol);
            return rejected(c, RejectReason.NO_LIQUIDITY,
                    "modify to market order but no opposite resting liquidity");
        }
        if (book.hasCounterOrderOfAccount(order.getSide(), newPrice, newMarket, order.getAccountId())) {
            book.add(order);
            reReserveOldTerms(account, order, symbol);
            return rejected(c, RejectReason.SELF_TRADE,
                    "modified order " + c.orderId() + " would cross same-account resting order");
        }

        long newRemaining = c.qty() - order.getFilledQty();
        if (order.getSide() == Side.BUY) {
            if (!newMarket) {
                long need = newPrice * newRemaining;
                if (!account.reserveCash(need)) {
                    book.add(order);
                    reReserveOldTerms(account, order, symbol);
                    return rejected(c, RejectReason.INSUFFICIENT_CASH,
                            "modified buy needs " + need
                                    + " reserved cash but available=" + account.getAvailableCash());
                }
                reservedCashByOrder.put(order.getOrderId(), need);
            }
        } else {
            if (!account.reservePosition(symbol, newRemaining)) {
                book.add(order);
                reReserveOldTerms(account, order, symbol);
                return rejected(c, RejectReason.INSUFFICIENT_POSITION,
                        "modified sell needs " + newRemaining + " available " + symbol
                                + " but available=" + account.getAvailablePosition(symbol));
            }
            reservedQtyByOrder.put(order.getOrderId(), newRemaining);
        }

        // 应用新条款（改单后视为新的时间优先级：seq 取本次命令 seq）。
        order.setType(c.orderType());
        order.setPrice(newPrice);
        order.setOriginalQty(c.qty());
        order.setSeq(c.seq());

        MatchOutcome outcome = match(order, symbol);
        String message = "modified " + c.orderId() + "; " + describeOutcome(order, outcome);
        if (order.getStatus() == OrderStatus.NEW || order.getStatus() == OrderStatus.PARTIALLY_FILLED) {
            if (newMarket) {
                order.setStatus(OrderStatus.CANCELLED);
                order.setRejectReason(outcome.stopReason);
                releaseResidualReservation(account, order, symbol);
                message += "; MARKET remainder cancelled reason=" + outcome.stopReason;
            } else {
                book.add(order);
            }
        }
        return CommandResult.ok(c.seq(), tradeSeqsOf(outcome), message);
    }

    private void reReserveOldTerms(Account account, Order order, String symbol) {
        long remaining = order.getRemainingQty();
        if (order.getSide() == Side.BUY) {
            if (order.getType() == OrderType.LIMIT) {
                long amount = order.getPrice() * remaining;
                account.reserveCash(amount);
                reservedCashByOrder.put(order.getOrderId(), amount);
            }
        } else {
            account.reservePosition(symbol, remaining);
            reservedQtyByOrder.put(order.getOrderId(), remaining);
        }
    }

    /* ---------------- MATCHING CORE ---------------- */

    private static final class MatchOutcome {
        final List<Trade> trades = new ArrayList<>();
        RejectReason stopReason; // 市价单停止吃单原因
    }

    private MatchOutcome match(Order taker, String symbol) {
        MatchOutcome outcome = new MatchOutcome();
        OrderBook book = bookFor(symbol);
        Account takerAccount = accounts.get(taker.getAccountId());
        boolean market = taker.getType() == OrderType.MARKET;

        while (taker.getRemainingQty() > 0) {
            Order maker = taker.getSide() == Side.BUY ? book.bestAsk() : book.bestBid();
            if (maker == null) {
                outcome.stopReason = RejectReason.NO_LIQUIDITY;
                break;
            }
            if (!market) {
                boolean crosses = taker.getSide() == Side.BUY
                        ? taker.getPrice() >= maker.getPrice()
                        : taker.getPrice() <= maker.getPrice();
                if (!crosses) {
                    break; // 限价单：价格不再满足，剩余量挂簿
                }
            }

            long fillQty = Math.min(taker.getRemainingQty(), maker.getRemainingQty());
            long fillPrice = maker.getPrice();

            if (taker.getSide() == Side.BUY && market) {
                // 市价买入不预冻现金：逐档检查可用现金，买不起整档就买得起多少买多少。
                long available = takerAccount.getAvailableCash();
                long affordable = available / fillPrice;
                if (affordable <= 0) {
                    outcome.stopReason = RejectReason.INSUFFICIENT_CASH;
                    break;
                }
                fillQty = Math.min(fillQty, affordable);
            }

            // 结算（先释放本次成交量对应的冻结，再划转；maker 价成交）。
            taker.setFilledQty(taker.getFilledQty() + fillQty);
            maker.setFilledQty(maker.getFilledQty() + fillQty);

            long cost = fillQty * fillPrice;
            Account makerAccount = accounts.get(maker.getAccountId());
            // 先精确释放本次成交量对应的冻结（taker 与 maker 各自记账），再做资产划转。
            consumeReservation(taker, fillQty, symbol);
            consumeReservation(maker, fillQty, symbol);
            if (taker.getSide() == Side.BUY) {
                takerAccount.settleBuy(symbol, fillQty, cost, 0L);
                makerAccount.settleSell(symbol, fillQty, cost, 0L);
            } else {
                takerAccount.settleSell(symbol, fillQty, cost, 0L);
                makerAccount.settleBuy(symbol, fillQty, cost, 0L);
            }

            if (maker.getRemainingQty() == 0) {
                maker.setStatus(OrderStatus.FILLED);
                book.remove(maker);
            } else {
                maker.setStatus(OrderStatus.PARTIALLY_FILLED);
            }
            if (taker.getRemainingQty() == 0) {
                taker.setStatus(OrderStatus.FILLED);
            } else {
                taker.setStatus(OrderStatus.PARTIALLY_FILLED);
            }

            Trade trade = new Trade(nextTradeSeq++, lastSeq, symbol, fillPrice, fillQty,
                    taker.getOrderId(), taker.getAccountId(), taker.getSide(),
                    maker.getOrderId(), maker.getAccountId());
            trades.add(trade);
            outcome.trades.add(trade);
        }

        if (taker.getStatus() == OrderStatus.NEW && taker.getFilledQty() == 0) {
            // 未成交且是限价单（市价单带量进入循环后 0 成交的极端情形由调用方标记）。
            taker.setStatus(OrderStatus.NEW);
        }
        return outcome;
    }

    /* ---------------- reservation helpers ---------------- */

    /** 释放某订单当前剩余的全部冻结（撤单 / 成交结束 / 改单摘簿时调用）。 */
    private long releaseResidualReservation(Account account, Order order, String symbol) {
        long releasedCash = 0L;
        Long cash = reservedCashByOrder.remove(order.getOrderId());
        if (cash != null && cash > 0) {
            account.releaseCash(cash);
            releasedCash = cash;
        }
        Long qty = reservedQtyByOrder.remove(order.getOrderId());
        if (qty != null && qty > 0) {
            account.releasePosition(symbol, qty);
        }
        return releasedCash;
    }

    /**
     * 成交一笔后，按订单精确扣减冻结记账：
     *  - 限价买：每成交 1 股释放 limitPrice 冻结现金（成交实际支付额由 settleBuy 处理）；
     *  - 卖单（taker 预冻或 maker 挂簿冻结）：每成交 1 股释放 1 股冻结持仓。
     * 簿中被动买单价为 maker 价；簿中无市价买单。
     */
    private void consumeReservation(Order o, long fillQty, String symbol) {
        if (o.getSide() == Side.BUY) {
            if (o.getType() == OrderType.LIMIT) {
                long amount = o.getPrice() * fillQty;
                Long held = reservedCashByOrder.get(o.getOrderId());
                if (held == null || held < amount) {
                    throw new IllegalStateException("reservation bookkeeping mismatch for buy "
                            + o.getOrderId() + " held=" + held + " need=" + amount);
                }
                accountOf(o).releaseCash(amount);
                long left = held - amount;
                if (left == 0) {
                    reservedCashByOrder.remove(o.getOrderId());
                } else {
                    reservedCashByOrder.put(o.getOrderId(), left);
                }
            }
        } else {
            Long held = reservedQtyByOrder.get(o.getOrderId());
            if (held == null || held < fillQty) {
                throw new IllegalStateException("reservation bookkeeping mismatch for sell "
                        + o.getOrderId() + " held=" + held + " need=" + fillQty);
            }
            accountOf(o).releasePosition(symbol, fillQty);
            long left = held - fillQty;
            if (left == 0) {
                reservedQtyByOrder.remove(o.getOrderId());
            } else {
                reservedQtyByOrder.put(o.getOrderId(), left);
            }
        }
    }

    private Account accountOf(Order o) {
        Account a = accounts.get(o.getAccountId());
        if (a == null) {
            throw new IllegalStateException("missing account " + o.getAccountId());
        }
        return a;
    }

    private String describeOutcome(Order order, MatchOutcome outcome) {
        StringBuilder sb = new StringBuilder();
        sb.append(order.getSide()).append(' ').append(order.getType())
                .append(" filled=").append(order.getFilledQty()).append('/').append(order.getOriginalQty());
        if (!outcome.trades.isEmpty()) {
            sb.append(" trades=");
            for (Trade t : outcome.trades) {
                sb.append('#').append(t.tradeSeq())
                        .append('(').append(t.qty()).append('@').append(t.price())
                        .append(" vs ").append(t.makerOrderId()).append(')');
            }
        }
        sb.append(" status=").append(order.getStatus());
        return sb.toString();
    }

    private static long[] tradeSeqsOf(MatchOutcome outcome) {
        long[] ids = new long[outcome.trades.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = outcome.trades.get(i).tradeSeq();
        }
        return ids;
    }

    private static boolean noCounterLiquidity(OrderBook book, Side side) {
        return side == Side.BUY ? book.bestAsk() == null : book.bestBid() == null;
    }

    private static String safeSymbol(String s) {
        return s == null ? "" : s;
    }

    private OrderBook bookFor(String symbol) {
        return books.computeIfAbsent(symbol, OrderBook::new);
    }

    private static String symbolOfActive(Order order) {
        return order.getSymbol();
    }

    /* ---------------- concurrent collector ---------------- */

    /**
     * 并发收集器：多个提交线程并发调用 {@link Collector#add(Command)}（仅入内存队列，
     * 不触碰引擎状态），全部提交完毕后由单线程 {@link Collector#finish()}，
     * 收集器在引擎单一锁内将全部命令按 seq 全局排序、去重并逐条执行。
     *
     * 因此无论提交线程的调度顺序如何，执行顺序只由 seq 决定 => 成交序列与余额确定。
     */
    public static final class Collector {
        private final MatchingEngine engine;
        private final java.util.concurrent.ConcurrentLinkedQueue<Command> queue =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        private volatile boolean closed = false;

        Collector(MatchingEngine engine) {
            this.engine = engine;
        }

        /** 线程安全地收集一条命令；不做校验、不产生副作用。 */
        public void add(Command command) {
            if (closed) {
                throw new IllegalStateException("collector already closed");
            }
            queue.add(command);
        }

        /** 关闭并在引擎单锁内统一执行全部命令（按 seq 升序、批内重号拒绝）。 */
        public List<CommandResult> finish() {
            if (closed) {
                throw new IllegalStateException("collector already closed");
            }
            closed = true;
            List<Command> all = new ArrayList<>(queue);
            return engine.submitBatch(all);
        }
    }

    /** 新建一个并发命令收集器。 */
    public synchronized Collector newCollector() {
        return new Collector(this);
    }

    /* ---------------- snapshot / queries ---------------- */

    public synchronized Order getOrder(String orderId) {
        Order o = allOrders.get(orderId);
        return o == null ? null : copyOf(o);
    }

    public synchronized List<Trade> getTrades() {
        return List.copyOf(trades);
    }

    public synchronized EngineSnapshot snapshot() {
        Map<String, Order> ordersCopy = new LinkedHashMap<>();
        List<Order> activeCopy = new ArrayList<>();
        for (Order o : allOrders.values()) {
            Order copy = copyOf(o);
            ordersCopy.put(copy.getOrderId(), copy);
            if (o.getStatus() == OrderStatus.NEW || o.getStatus() == OrderStatus.PARTIALLY_FILLED) {
                activeCopy.add(copy);
            }
        }
        List<EngineSnapshot.AccountSnapshot> accountCopies = new ArrayList<>();
        for (Account a : accounts.values()) {
            accountCopies.add(new EngineSnapshot.AccountSnapshot(
                    a.getAccountId(), a.getCash(), a.getReservedCash(),
                    new LinkedHashMap<>(a.getPositions()),
                    new LinkedHashMap<>(a.getReservedPositions())));
        }
        return new EngineSnapshot(lastSeq + 1, nextTradeSeq,
                ordersCopy, List.copyOf(activeCopy), List.copyOf(trades), accountCopies);
    }

    private static Order copyOf(Order o) {
        Order copy = new Order(o.getOrderId(), o.getAccountId(), o.getSymbol(), o.getSide(), o.getType(),
                o.getPrice(), o.getOriginalQty(), o.getSeq());
        copy.setFilledQty(o.getFilledQty());
        copy.setStatus(o.getStatus());
        copy.setRejectReason(o.getRejectReason());
        return copy;
    }

    synchronized long nextTradeSeqValue() {
        return nextTradeSeq;
    }
}
