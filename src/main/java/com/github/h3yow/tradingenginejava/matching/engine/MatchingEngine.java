package com.github.h3yow.tradingenginejava.matching.engine;

import com.github.h3yow.tradingenginejava.matching.model.Account;
import com.github.h3yow.tradingenginejava.matching.model.BatchResult;
import com.github.h3yow.tradingenginejava.matching.model.Command;
import com.github.h3yow.tradingenginejava.matching.model.CommandResult;
import com.github.h3yow.tradingenginejava.matching.model.Order;
import com.github.h3yow.tradingenginejava.matching.model.OrderStatus;
import com.github.h3yow.tradingenginejava.matching.model.OrderType;
import com.github.h3yow.tradingenginejava.matching.model.RejectReason;
import com.github.h3yow.tradingenginejava.matching.model.Rejection;
import com.github.h3yow.tradingenginejava.matching.model.Side;
import com.github.h3yow.tradingenginejava.matching.model.Trade;
import com.github.h3yow.tradingenginejava.matching.snapshot.Snapshot;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 内存撮合引擎。
 *
 * <p><b>确定性核心：</b>所有指令通过 {@link #apply(Command)} <i>单线程串行</i>应用；
 * 状态变更、成交编号、拒绝记录都只由指令的全序（seq）决定，与调用方线程调度无关。
 * 成交价格取挂单价（maker price）；同一价位严格按下单 seq 的 FIFO（时间优先）。
 *
 * <p><b>资金/持仓守恒：</b>限价委托入簿即冻结买方现金（price×qty）或卖方持仓；
 * 成交时按冻结逐笔结算，撤单/改单/市价余量全部解冻，账户四个分量恒非负。
 * 市价买入不预先冻结，逐价位检查可用现金；现金/持仓不足以吃完整笔的挂单会被跳过，
 * 最终未成交余量按 IOC 撤销——绝不超卖、绝不负余额。
 *
 * <p><b>幂等：</b>重复 seq 直接以 DUPLICATE_COMMAND 拒绝且不触碰状态，因此同一事件日志
 * （含从快照恢复后的增量日志）可安全重复回放。
 */
public final class MatchingEngine {

    private static final RiskConfig DEFAULT_RISK =
            new RiskConfig(1_000, new BigDecimal("1000000000"));

    private final RiskConfig riskConfig;

    /**
     * 已处理水位。seq 从 0 起算（也支持从 1 起算——从快照恢复时以快照水位为准）。
     * 并发乱序到达时，超前序号在 pendingCommands 中等待，严格按 seq 连续应用。
     */
    private long lastSeq = -1L;
    private long nextOrderId = 1L;
    private long nextTradeId = 1L;

    private final Map<String, Account> accounts = new TreeMap<>();
    private final Map<String, OrderBook> books = new TreeMap<>();
    private final Map<String, Order> ordersByClientId = new LinkedHashMap<>();
    private final List<Trade> trades = new ArrayList<>();
    private final List<Rejection> rejections = new ArrayList<>();

    /** 串行应用的协调锁：所有状态变更都在该锁内完成，顺序严格等于 seq 全序。 */
    private final Object guard = new Object();

    public MatchingEngine() {
        this(DEFAULT_RISK);
    }

    public MatchingEngine(RiskConfig riskConfig) {
        this.riskConfig = riskConfig;
    }

    // ---------------- 账户 ----------------

    public void registerAccount(String accountId, BigDecimal cash, long position) {
        if (accounts.containsKey(accountId)) {
            throw new IllegalArgumentException("账户已存在: " + accountId);
        }
        accounts.put(accountId, new Account(accountId, cash, position));
    }

    public Account getAccount(String accountId) {
        Account a = accounts.get(accountId);
        if (a == null) {
            throw new IllegalArgumentException("未知账户: " + accountId);
        }
        return a;
    }

    /** 按 clientOrderId 查询委托（含已终结），不存在返回 null。 */
    public Order getOrder(String clientOrderId) {
        return ordersByClientId.get(clientOrderId);
    }

    /** 全部账户的只读快照视图（按账户名排序），供对账摘要/指纹核对使用。 */
    public Map<String, Account> getAccounts() {
        synchronized (guard) {
            return new TreeMap<>(accounts);
        }
    }

    /** 全部委托（含已终结）的只读列表，按委托登记顺序。 */
    public List<Order> getOrders() {
        synchronized (guard) {
            return List.copyOf(ordersByClientId.values());
        }
    }

    // ---------------- 指令入口 ----------------

    public long getLastSeq() {
        return lastSeq;
    }

    public long getNextOrderId() {
        return nextOrderId;
    }

    public long getNextTradeId() {
        return nextTradeId;
    }

    public List<Trade> getTrades() {
        return List.copyOf(trades);
    }

    public List<Rejection> getRejections() {
        return List.copyOf(rejections);
    }

    /**
     * 声明全序起点：第一条真实指令的 seq 必须等于 {@code startSeq}。
     * 空引擎上设定水位；已推进或已从快照恢复的引擎仅做一致性校验。
     */
    public void prime(long startSeq) {
        synchronized (guard) {
            if (lastSeq == -1L) {
                lastSeq = startSeq - 1;
            } else if (lastSeq < startSeq - 1) {
                throw new IllegalStateException(
                        "引擎水位 lastSeq=" + lastSeq + " 与批次起点 " + startSeq + " 不连续");
            }
        }
    }

    /**
     * 串行应用一条指令。所有状态变更与成交编号由此调用序确定。
     * seq 必须严格递增且连续（首个 seq 由 {@link #prime(long)} 声明，默认从 0 起）：
     * 小于等于水位的 seq 按 DUPLICATE_COMMAND 拒绝且不触碰任何状态，因此同一事件日志
     * （含快照恢复后的增量日志）可安全重复回放，绝不重复成交。
     */
    public CommandResult apply(Command cmd) {
        Objects.requireNonNull(cmd, "cmd");
        synchronized (guard) {
            long seq = cmd.getSeq();
            if (seq <= lastSeq) {
                return reject(new Rejection(seq, cmd.getClientOrderId(),
                        RejectReason.DUPLICATE_COMMAND,
                        "seq=" + seq + " 已应用（lastSeq=" + lastSeq + "）"));
            }
            if (lastSeq == -1L) {
                // 空引擎首条指令自动确定全序起点（直接单线程使用时无需显式 prime）
                lastSeq = seq - 1;
            }
            if (seq != lastSeq + 1) {
                throw new IllegalStateException(
                        "指令 seq=" + seq + " 与水位 lastSeq=" + lastSeq + " 不连续");
            }
            CommandResult result = directApply(cmd);
            lastSeq = seq;
            return result;
        }
    }

    /** 已持有 guard 且确认 seq 连续时的内部直执路径（含改单派生的下单）。 */
    private CommandResult directApply(Command c) {
        return switch (c.getType()) {
            case PLACE -> applyPlace(c);
            case CANCEL -> applyCancel(c, false, -1L);
            case REPLACE -> applyReplace(c);
        };
    }

    // ---------------- 委托组原子提交 ----------------

    /**
     * 原子提交一组委托：组内严格按给定顺序（seq 必须连续）逐条撮合；
     * 任意一条不合法则<b>整批回滚</b>——成交、委托、冻结、编号水位全部复原，
     * 对外零残留（被回滚的 clientOrderId 可再次使用）。
     *
     * <p>无论成败，整批 seq 都被消耗（lastSeq 推进到批末），保证事件日志连续、
     * 回放确定性一致：成功时每条结果与单条提交同构；回滚时触发失败的那条保留自身
     * 拒绝原因，组内其余指令记 {@link RejectReason#BATCH_ROLLED_BACK}，
     * 以此区分“整组没落地”与“某条本身的问题”。
     */
    public BatchResult applyBatch(List<Command> batch) {
        Objects.requireNonNull(batch, "batch");
        if (batch.isEmpty()) {
            throw new IllegalArgumentException("委托组不能为空");
        }
        synchronized (guard) {
            long startSeq = batch.get(0).getSeq();
            for (int i = 1; i < batch.size(); i++) {
                if (batch.get(i).getSeq() != startSeq + i) {
                    throw new IllegalStateException(
                            "委托组内 seq 不连续: 第 " + i + " 条 seq="
                                    + batch.get(i).getSeq() + "，期望 " + (startSeq + i));
                }
            }
            if (lastSeq == -1L) {
                lastSeq = startSeq - 1; // 空引擎自动锚定全序起点（同 apply）
            }
            if (startSeq <= lastSeq) {
                // 与 apply 的幂等语义一致：整批重复提交逐条记 DUPLICATE_COMMAND，不触碰状态
                List<CommandResult> dup = new ArrayList<>();
                for (Command c : batch) {
                    Rejection r = new Rejection(c.getSeq(), c.getClientOrderId(),
                            RejectReason.DUPLICATE_COMMAND,
                            "委托组 seq=" + c.getSeq() + " 已应用（lastSeq=" + lastSeq + "）");
                    rejections.add(r);
                    dup.add(CommandResult.rejected(r));
                }
                return new BatchResult(startSeq, false, dup);
            }
            if (startSeq != lastSeq + 1) {
                throw new IllegalStateException(
                        "委托组起始 seq=" + startSeq + " 与水位 lastSeq=" + lastSeq + " 不连续");
            }

            StateMemento memento = saveState();
            List<CommandResult> results = new ArrayList<>(batch.size());
            int failAt = -1;
            for (int i = 0; i < batch.size(); i++) {
                CommandResult r = directApply(batch.get(i));
                results.add(r);
                if (!r.accepted()) {
                    failAt = i;
                    break; // 后续指令不再执行，整批回滚
                }
            }
            lastSeq = startSeq + batch.size() - 1; // 无论成败，整批 seq 均被消耗

            if (failAt < 0) {
                return new BatchResult(startSeq, true, results);
            }
            restoreState(memento);
            // 重建逐条判定：失败条保留自身原因，其余记 BATCH_ROLLED_BACK
            List<CommandResult> rolledBack = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                Command c = batch.get(i);
                Rejection r = (i == failAt)
                        ? results.get(failAt).rejection()
                        : new Rejection(c.getSeq(), c.getClientOrderId(),
                                RejectReason.BATCH_ROLLED_BACK,
                                "委托组第 " + failAt + " 条（seq="
                                        + batch.get(failAt).getSeq() + "）不合法，整批回滚");
                rejections.add(r);
                rolledBack.add(CommandResult.rejected(r));
            }
            return new BatchResult(startSeq, false, rolledBack);
        }
    }

    /** 状态备忘录：深拷贝账户/委托/订单簿，成交与拒绝为不可变记录仅复制列表。 */
    private StateMemento saveState() {
        StateMemento m = new StateMemento();
        m.nextOrderId = nextOrderId;
        m.nextTradeId = nextTradeId;
        m.accounts = new TreeMap<>();
        accounts.forEach((id, a) -> m.accounts.put(id, a.copy()));
        m.orders = new LinkedHashMap<>();
        ordersByClientId.forEach((coid, o) -> m.orders.put(coid, o.copy()));
        Map<Long, Order> byId = new LinkedHashMap<>();
        m.orders.values().forEach(o -> byId.put(o.getId(), o));
        m.books = new TreeMap<>();
        books.forEach((symbol, book) -> {
            OrderBook copy = new OrderBook(symbol);
            for (Order live : book.liveOrdersInBookOrder()) {
                copy.add(byId.get(live.getId()));
            }
            m.books.put(symbol, copy);
        });
        m.trades = new ArrayList<>(trades);
        m.rejections = new ArrayList<>(rejections);
        return m;
    }

    private void restoreState(StateMemento m) {
        nextOrderId = m.nextOrderId;
        nextTradeId = m.nextTradeId;
        accounts.clear();
        accounts.putAll(m.accounts);
        ordersByClientId.clear();
        ordersByClientId.putAll(m.orders);
        books.clear();
        books.putAll(m.books);
        trades.clear();
        trades.addAll(m.trades);
        rejections.clear();
        rejections.addAll(m.rejections);
    }

    private static final class StateMemento {
        long nextOrderId;
        long nextTradeId;
        Map<String, Account> accounts;
        Map<String, Order> orders;
        Map<String, OrderBook> books;
        List<Trade> trades;
        List<Rejection> rejections;
    }

    private CommandResult reject(Rejection r) {
        rejections.add(r);
        return CommandResult.rejected(r);
    }

    // ---------------- 下单 ----------------

    private CommandResult applyPlace(Command cmd) {
        String accountId = cmd.getAccountId();
        String coid = cmd.getClientOrderId();

        if (accountId == null || cmd.getSymbol() == null || coid == null
                || cmd.getSide() == null || cmd.getOrderType() == null) {
            return reject(new Rejection(cmd.getSeq(), coid, RejectReason.INVALID_COMMAND,
                    "下单指令缺少必要字段: " + cmd));
        }
        Account account = accounts.get(accountId);
        if (account == null) {
            return reject(new Rejection(cmd.getSeq(), coid, RejectReason.INVALID_COMMAND,
                    "未知账户: " + accountId));
        }
        if (ordersByClientId.containsKey(coid)) {
            return reject(new Rejection(cmd.getSeq(), coid, RejectReason.DUPLICATE_COMMAND,
                    "clientOrderId 已存在: " + coid));
        }
        if (cmd.getQuantity() <= 0) {
            return reject(new Rejection(cmd.getSeq(), coid, RejectReason.INVALID_QUANTITY,
                    "下单数量必须为正整数: " + cmd.getQuantity()));
        }
        if (cmd.getOrderType() == OrderType.LIMIT
                && (cmd.getPrice() == null || cmd.getPrice().signum() <= 0)) {
            return reject(new Rejection(cmd.getSeq(), coid, RejectReason.INVALID_PRICE,
                    "限价必须为正数: " + cmd.getPrice()));
        }

        BigDecimal price = cmd.getPrice();
        long qty = cmd.getQuantity();
        Side side = cmd.getSide();
        String symbol = cmd.getSymbol();
        OrderBook book = books.computeIfAbsent(symbol, OrderBook::new);

        // 风控：在途委托数（市价单 IOC 不入簿，不占名额）
        if (cmd.getOrderType() == OrderType.LIMIT
                && book.liveOrderCount(accountId) >= riskConfig.maxOpenOrdersPerAccount()) {
            return reject(new Rejection(cmd.getSeq(), coid, RejectReason.LIMIT_EXCEEDED,
                    "账户 " + accountId + " 在途委托数已达上限 "
                            + riskConfig.maxOpenOrdersPerAccount()));
        }

        // 市价单的名义金额按当下最优对手价估算（无对手盘则无流动性，直接拒绝）
        BigDecimal valuationPrice = price;
        if (cmd.getOrderType() == OrderType.MARKET) {
            valuationPrice = book.bestOppositePrice(side);
            if (valuationPrice == null) {
                return reject(new Rejection(cmd.getSeq(), coid,
                        RejectReason.MARKET_NO_LIQUIDITY,
                        "市价单无对手盘: " + symbol + " " + side));
            }
        }
        BigDecimal notional = valuationPrice.multiply(BigDecimal.valueOf(qty));
        if (notional.compareTo(riskConfig.maxOrderNotional()) > 0) {
            return reject(new Rejection(cmd.getSeq(), coid, RejectReason.LIMIT_EXCEEDED,
                    "名义金额 " + notional + " 超过单笔上限 " + riskConfig.maxOrderNotional()));
        }

        // 自成交预检（价格可及范围内存在同账户对手挂单即整单拒绝，绝不与自己成交）
        if (book.hasSameAccountOnOpposite(side, accountId, price)) {
            return reject(new Rejection(cmd.getSeq(), coid, RejectReason.SELF_TRADE,
                    "账户 " + accountId + " 的 " + side + " 单会与自身对手挂单成交"));
        }

        // 入簿前校验（限价单保证挂簿余量的冻结一定成功；市价单按逐价位即时校验）
        if (cmd.getOrderType() == OrderType.LIMIT) {
            if (side == Side.BUY && account.getAvailableCash().compareTo(notional) < 0) {
                return reject(new Rejection(cmd.getSeq(), coid,
                        RejectReason.INSUFFICIENT_CASH,
                        "买入冻结 " + notional + " 超过可用现金 " + account.getAvailableCash()));
            }
            if (side == Side.SELL && account.getAvailablePosition() < qty) {
                return reject(new Rejection(cmd.getSeq(), coid,
                        RejectReason.INSUFFICIENT_POSITION,
                        "卖出冻结 " + qty + " 超过可用持仓 " + account.getAvailablePosition()));
            }
        }

        Order order = new Order(nextOrderId++, cmd.getSeq(), coid, accountId, symbol,
                side, cmd.getOrderType(), price, qty, OrderStatus.NEW);
        ordersByClientId.put(coid, order);

        List<Trade> fills = match(book, order);

        if (order.getOpenQty() > 0) {
            if (order.getType() == OrderType.LIMIT) {
                // 仅为挂簿余量冻结（即时成交部分已在 match 中直接结算，未被冻结/重复冻结）
                if (side == Side.BUY) {
                    account.reserveCash(price.multiply(BigDecimal.valueOf(order.getOpenQty())));
                } else {
                    account.reservePosition(order.getOpenQty());
                }
                book.add(order);
            } else {
                // 市价 IOC：剩余量显式撤销，filled + canceled = total，openQty 归零
                order.recordCancel(order.getOpenQty());
            }
        }
        book.prune();
        return CommandResult.accepted(cmd.getSeq(), coid, order.getStatus(),
                order.getOpenQty(), fills);
    }

    /**
     * 撮合吃单：按价格优先/时间优先扫描对手挂单。
     * 价格条件：BUY 限价时 makerPrice &lt;= limit；SELL 限价时 makerPrice &gt;= limit。
     * 任何一方余额/持仓不足以完成该挂单的可成交量时，跳过该挂单（不得负余额、不得超卖）。
     */
    private List<Trade> match(OrderBook book, Order taker) {
        List<Trade> fills = new ArrayList<>();
        Side takerSide = taker.getSide();
        BigDecimal limit = taker.getPrice(); // 市价为 null
        Iterator<Order> it = book.oppositeIterator(takerSide);
        scan:
        while (taker.getOpenQty() > 0 && it.hasNext()) {
            Order maker = it.next();
            if (!maker.isLive()) {
                continue;
            }
            if (limit != null) {
                boolean priceOk = takerSide == Side.BUY
                        ? limit.compareTo(maker.getPrice()) >= 0
                        : maker.getPrice().compareTo(limit) >= 0;
                if (!priceOk) {
                    break; // TreeMap 有序，越过最优可成交价后后续都更差
                }
            }
            if (maker.getAccountId().equals(taker.getAccountId())) {
                // 双保险：正常已被预检拦截；回放恢复的簿中也不允许自吃
                continue;
            }
            long qty = Math.min(taker.getOpenQty(), maker.getOpenQty());
            BigDecimal makerPrice = maker.getPrice();
            Account takerAcct = accounts.get(taker.getAccountId());
            Account makerAcct = accounts.get(maker.getAccountId());

            // 结算前按可承受能力裁剪成交量：任何情况下都不得负余额、不得超卖。
            // taker 若已在撮合前为 openQty 全额冻结（改单重建），买单预算来自冻结现金、
            // 卖单预算来自冻结持仓；普通新单预算来自可用额度。
            boolean takerPreReserved = taker.isPreReserved();
            if (takerSide == Side.BUY) {
                BigDecimal buyBudget = takerPreReserved
                        ? takerAcct.getReservedCash()
                        : takerAcct.getAvailableCash();
                long affordable = buyBudget.divideToIntegralValue(makerPrice).longValueExact();
                if (affordable <= 0) {
                    break scan; // 一个都买不起，更贵价位更无可能
                }
                qty = Math.min(qty, affordable);
                if (makerAcct.getReservedPosition() < qty) {
                    continue;
                }
                BigDecimal cost = makerPrice.multiply(BigDecimal.valueOf(qty));
                takerAcct.settle(Side.BUY, qty, cost, takerPreReserved);
                makerAcct.settle(Side.SELL, qty, cost, true);
            } else {
                long sellBudget = takerPreReserved
                        ? takerAcct.getReservedPosition()
                        : takerAcct.getAvailablePosition();
                qty = Math.min(qty, sellBudget);
                if (qty <= 0) {
                    break scan;
                }
                BigDecimal cost = makerPrice.multiply(BigDecimal.valueOf(qty));
                if (makerAcct.getReservedCash().compareTo(cost) < 0) {
                    // 理论上买方挂单已冻结对应现金，此处仅为防御
                    continue;
                }
                takerAcct.settle(Side.SELL, qty, cost, takerPreReserved);
                makerAcct.settle(Side.BUY, qty, cost, true);
            }

            taker.recordFill(qty);
            maker.recordFill(qty);
            Trade trade = new Trade(taker.getSeq(), book.getSymbol(), nextTradeId++,
                    taker.getClientOrderId(), maker.getClientOrderId(),
                    takerSide == Side.BUY ? taker.getAccountId() : maker.getAccountId(),
                    takerSide == Side.SELL ? taker.getAccountId() : maker.getAccountId(),
                    takerSide, maker.getPrice(), qty);
            fills.add(trade);
            trades.add(trade);
        }
        return fills;
    }

    // ---------------- 撤单 ----------------

    /**
     * @param releaseQtyOverride 改单撤旧时传入“旧单挂簿余量”，避免按新总量解冻；普通撤单传 -1
     */
    private CommandResult applyCancel(Command cmd, boolean fromReplace, long releaseQtyOverride) {
        String coid = cmd.getClientOrderId();
        Order order = ordersByClientId.get(coid);
        if (order == null
                || (!fromReplace && (order.getStatus() == OrderStatus.FILLED
                || order.getStatus() == OrderStatus.CANCELED
                || order.getStatus() == OrderStatus.REJECTED))) {
            return reject(new Rejection(cmd.getSeq(), coid, RejectReason.UNKNOWN_ORDER,
                    (fromReplace ? "改单" : "撤单") + "引用未知或已终结委托: " + coid));
        }
        if (!order.getAccountId().equals(cmd.getAccountId())) {
            return reject(new Rejection(cmd.getSeq(), coid, RejectReason.UNKNOWN_ORDER,
                    "委托属于其他账户: " + coid + " 归属 " + order.getAccountId()));
        }
        long open = releaseQtyOverride >= 0 ? releaseQtyOverride : order.getOpenQty();
        Account account = accounts.get(order.getAccountId());
        if (order.getType() == OrderType.LIMIT) {
            if (order.getSide() == Side.BUY) {
                account.releaseCash(order.getPrice().multiply(BigDecimal.valueOf(open)));
            } else {
                account.releasePosition(open);
            }
        }
        order.recordCancel(open);
        OrderBook book = books.get(order.getSymbol());
        if (book != null) {
            book.prune();
        }
        return CommandResult.accepted(cmd.getSeq(), coid, OrderStatus.CANCELED, 0L, List.of());
    }

    // ---------------- 改单 ----------------

    private CommandResult applyReplace(Command cmd) {
        String coid = cmd.getClientOrderId();
        Order old = ordersByClientId.get(coid);
        if (old == null || !old.isLive()) {
            return reject(new Rejection(cmd.getSeq(), coid, RejectReason.UNKNOWN_ORDER,
                    "改单引用未知或已终结委托: " + coid));
        }
        if (!old.getAccountId().equals(cmd.getAccountId())) {
            return reject(new Rejection(cmd.getSeq(), coid, RejectReason.UNKNOWN_ORDER,
                    "委托属于其他账户: " + coid));
        }
        if (old.getType() != OrderType.LIMIT || cmd.getNewPrice() == null
                || cmd.getNewPrice().signum() <= 0) {
            return reject(new Rejection(cmd.getSeq(), coid, RejectReason.INVALID_PRICE,
                    "改单目标价非法（改单仅支持限价单）: " + cmd.getNewPrice()));
        }
        long newTotalQty = cmd.getNewQuantity() == null ? -1L : cmd.getNewQuantity();
        if (newTotalQty <= old.getFilledQty()) {
            return reject(new Rejection(cmd.getSeq(), coid, RejectReason.INVALID_QUANTITY,
                    "改单新总量 " + newTotalQty + " 必须大于已成交量 " + old.getFilledQty()));
        }
        BigDecimal newPrice = cmd.getNewPrice();
        Side side = old.getSide();
        String symbol = old.getSymbol();
        Account account = accounts.get(old.getAccountId());
        OrderBook book = books.computeIfAbsent(symbol, OrderBook::new);
        long newOpenQty = newTotalQty - old.getFilledQty();

        // 风控：改单后仍占 1 个在途名额；名义金额按新价新量重校
        BigDecimal newNotional = newPrice.multiply(BigDecimal.valueOf(newTotalQty));
        if (newNotional.compareTo(riskConfig.maxOrderNotional()) > 0) {
            return reject(new Rejection(cmd.getSeq(), coid, RejectReason.LIMIT_EXCEEDED,
                    "改单后名义金额 " + newNotional + " 超过单笔上限 "
                            + riskConfig.maxOrderNotional()));
        }

        // 撤旧前做最坏情况（0 成交、newOpenQty 全部挂簿）足额校验：
        // 撤旧释放的是旧冻结，重建后需要为新余量提供足额可用，不足则整单拒绝、旧单不动。
        BigDecimal worstCash = newPrice.multiply(BigDecimal.valueOf(newOpenQty));
        if (side == Side.BUY) {
            BigDecimal cashAfterRelease = account.getAvailableCash()
                    .add(old.getPrice().multiply(BigDecimal.valueOf(old.getOpenQty())));
            if (cashAfterRelease.compareTo(worstCash) < 0) {
                return reject(new Rejection(cmd.getSeq(), coid, RejectReason.INSUFFICIENT_CASH,
                        "改单买入最坏需冻结 " + worstCash + "，撤旧后可用仅 " + cashAfterRelease));
            }
        } else if (account.getAvailablePosition() + old.getOpenQty() < newOpenQty) {
            return reject(new Rejection(cmd.getSeq(), coid, RejectReason.INSUFFICIENT_POSITION,
                    "改单卖出最坏需冻结 " + newOpenQty + "，撤旧后可用持仓仅 "
                            + (account.getAvailablePosition() + old.getOpenQty())));
        }

        // 1) 撤旧：从簿上摘除旧委托并解冻其旧余量（旧 filledQty 历史保留）
        long oldOpen = old.getOpenQty();
        if (side == Side.BUY) {
            account.releaseCash(old.getPrice().multiply(BigDecimal.valueOf(oldOpen)));
        } else {
            account.releasePosition(oldOpen);
        }
        book.remove(old);

        // 2) 自成交预检（按新价）
        if (book.hasSameAccountOnOpposite(side, account.getAccountId(), newPrice)) {
            // 恢复旧委托：重新挂回并重新冻结，整单拒绝
            if (side == Side.BUY) {
                account.reserveCash(old.getPrice().multiply(BigDecimal.valueOf(oldOpen)));
            } else {
                account.reservePosition(oldOpen);
            }
            book.add(old);
            return reject(new Rejection(cmd.getSeq(), coid, RejectReason.SELF_TRADE,
                    "改单后新价 " + newPrice + " 会造成自成交"));
        }

        // 3) 重建委托（保留 id/filled）；先为新 openQty 全额冻结再撮合，
        //    即时成交部分从冻结中结算，未成交余量的冻结原样保留并挂簿
        old.rebuildForReplace(newPrice, newTotalQty);
        if (side == Side.BUY) {
            account.reserveCash(newPrice.multiply(BigDecimal.valueOf(newOpenQty)));
        } else {
            account.reservePosition(newOpenQty);
        }
        old.setPreReserved(true);
        List<Trade> fills = match(book, old);
        old.setPreReserved(false);

        if (old.getOpenQty() > 0) {
            // 未成交余量仍处于冻结状态（成交部分已在 match 中消耗），直接挂簿
            book.add(old);
        }
        book.prune();
        return CommandResult.accepted(cmd.getSeq(), coid, old.getStatus(),
                old.getOpenQty(), fills);
    }

    // ---------------- 快照 ----------------

    /**
     * 不停机一致性快照。
     *
     * <p><b>一致性切点：</b>全部状态变更都在 {@code guard} 内、以整条指令为粒度完成，
     * 因此快照要么落在第 k 条指令<b>之后</b>、第 k+1 条<b>之前</b>，绝不夹在指令中间；
     * 导出内容里的 {@code lastSeq} 就是这个明确切点。其他账户的并发提交仅在
     * 内存深拷贝期间被短暂阻塞（不覆盖 JSON 序列化），拷贝完成后写入立即继续。
     *
     * <p><b>续跑等价：</b>快照 + lastSeq 之后的增量日志在任意空引擎上恢复续跑，
     * 最终成交序列、每笔委托三分量（已成交/已撤销/剩余）与账户四分量，
     * 与全程未导出的实例逐项一致。
     */
    public Snapshot snapshot() {
    synchronized (guard) {
        Snapshot s = new Snapshot();
        s.lastSeq = lastSeq;
        s.nextOrderId = nextOrderId;
        s.nextTradeId = nextTradeId;

        Map<String, Object> acc = new LinkedHashMap<>();
        for (Map.Entry<String, Account> e : accounts.entrySet()) {
            Account a = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cash", a.getCash().toPlainString());
            m.put("reservedCash", a.getReservedCash().toPlainString());
            m.put("availableCash", a.getAvailableCash().toPlainString());
            m.put("position", a.getPosition());
            m.put("reservedPosition", a.getReservedPosition());
            m.put("availablePosition", a.getAvailablePosition());
            acc.put(e.getKey(), m);
        }
        s.accounts = acc;

        Map<String, Object> bs = new LinkedHashMap<>();
        for (Map.Entry<String, OrderBook> e : books.entrySet()) {
            bs.put(e.getKey(), e.getValue().snapshot());
        }
        s.symbolBooks = bs;

        List<Object> ts = new ArrayList<>();
        for (Trade t : trades) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", t.seq());
            m.put("symbol", t.symbol());
            m.put("tradeId", t.tradeId());
            m.put("takerClientOrderId", t.takerClientOrderId());
            m.put("makerClientOrderId", t.makerClientOrderId());
            m.put("buyerAccountId", t.buyerAccountId());
            m.put("sellerAccountId", t.sellerAccountId());
            m.put("takerSide", t.takerSide().name());
            m.put("price", t.price().toPlainString());
            m.put("quantity", t.quantity());
            ts.add(m);
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("count", ts.size());
        extra.put("list", ts);
        s.trades = extra;

        List<Object> rs = new ArrayList<>();
        for (Rejection r : rejections) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", r.seq());
            m.put("clientOrderId", r.clientOrderId());
            m.put("reason", r.reason().name());
            m.put("detail", r.detail());
            rs.add(m);
        }
        s.rejections = rs;

        // 全部委托（含已终结），保证恢复后“未知委托”判定与历史一致
        List<Object> os = new ArrayList<>();
        for (Order o : ordersByClientId.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.getId());
            m.put("seq", o.getSeq());
            m.put("clientOrderId", o.getClientOrderId());
            m.put("accountId", o.getAccountId());
            m.put("symbol", o.getSymbol());
            m.put("side", o.getSide().name());
            m.put("type", o.getType().name());
            m.put("price", o.getPrice() == null ? null : o.getPrice().toPlainString());
            m.put("totalQty", o.getTotalQty());
            m.put("filledQty", o.getFilledQty());
            m.put("canceledQty", o.getCanceledQty());
            m.put("status", o.getStatus().name());
            os.add(m);
        }
        s.orders = os;
        return s;
        }
    }

    /**
     * 导出快照并序列化为 JSON。内存深拷贝在 {@code guard} 内完成（确定切点），
     * JSON 序列化在锁外进行——写入线程最多被阻塞一次内存拷贝的时长，
     * 序列化耗时（通常远大于拷贝）完全不阻塞撮合。
     */
    public String snapshotJson() {
        return com.github.h3yow.tradingenginejava.matching.util.JsonIO.toJson(snapshot());
    }

    /**
     * 盘后对账摘要：在 {@code guard} 内取数，落在明确的指令切点（lastSeq）上。
     * 同一状态两次取摘要逐字节相同；两台实例摘要一致即状态一致，
     * 不一致时可用 {@code ReconciliationDigest.diff} 定位到账户/标的/维度。
     */
    public com.github.h3yow.tradingenginejava.matching.digest.ReconciliationDigest reconciliationDigest() {
        synchronized (guard) {
            return com.github.h3yow.tradingenginejava.matching.digest.ReconciliationDigest
                    .build(lastSeq, accounts, ordersByClientId.values(), trades);
        }
    }

    @SuppressWarnings("unchecked")
    public void restore(Snapshot s) {
        synchronized (guard) {
        accounts.clear();
        books.clear();
        ordersByClientId.clear();
        trades.clear();
        rejections.clear();

        lastSeq = s.lastSeq;
        nextOrderId = s.nextOrderId;
        nextTradeId = s.nextTradeId;

        for (Map.Entry<String, Object> e : s.accounts.entrySet()) {
            Map<String, Object> m = (Map<String, Object>) e.getValue();
            Account a = new Account(e.getKey(),
                    new BigDecimal((String) m.get("cash")),
                    ((Number) m.get("position")).longValue());
            // 通过冻结/解冻 API 复原 reserved 分量，保证恒等式
            long reservedPos = ((Number) m.get("reservedPosition")).longValue();
            if (reservedPos > 0) {
                a.reservePosition(reservedPos);
            }
            BigDecimal reservedCash = new BigDecimal((String) m.get("reservedCash"));
            if (reservedCash.signum() > 0) {
                a.reserveCash(reservedCash);
            }
            accounts.put(e.getKey(), a);
        }

        for (Order o : restoreOrders(s)) {
            ordersByClientId.put(o.getClientOrderId(), o);
        }

        // 按快照中订单簿的精确排队顺序重建：改单会把委托移到价位队尾，
        // 若按委托登记顺序重建会改变价位内 FIFO，导致续撮成交序列偏离。
        // symbolBooks 记录了 bids 降序/asks 升序、价位内 FIFO 的真实顺序，据此逐笔挂回。
        for (Map.Entry<String, Object> e : s.symbolBooks.entrySet()) {
            OrderBook book = books.computeIfAbsent(e.getKey(), OrderBook::new);
            if (!(e.getValue() instanceof Map<?, ?> bookMap)) {
                continue;
            }
            for (String sideKey : List.of("bids", "asks")) {
                if (((Map<?, ?>) bookMap).get(sideKey) instanceof List<?> levels) {
                    for (Object lvl : levels) {
                        if (!(lvl instanceof Map<?, ?> level)) {
                            continue;
                        }
                        if (((Map<?, ?>) level).get("orders") instanceof List<?> orderList) {
                            for (Object om : orderList) {
                                if (!(om instanceof Map<?, ?> om2)) {
                                    continue;
                                }
                                Order o = ordersByClientId.get(
                                        (String) ((Map<?, ?>) om2).get("clientOrderId"));
                                if (o != null && o.isLive() && o.getType() == OrderType.LIMIT) {
                                    book.add(o);
                                }
                            }
                        }
                    }
                }
            }
        }

        restoreTrades(s);
        restoreRejections(s);
        }
    }

    private List<Order> restoreOrders(@SuppressWarnings("unchecked") Snapshot s) {
        List<Order> list = new ArrayList<>();
        for (Object raw : s.orders) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) raw;
            Order o = new Order(
                    ((Number) m.get("id")).longValue(),
                    ((Number) m.get("seq")).longValue(),
                    (String) m.get("clientOrderId"),
                    (String) m.get("accountId"),
                    (String) m.get("symbol"),
                    Side.valueOf((String) m.get("side")),
                    OrderType.valueOf((String) m.get("type")),
                    m.get("price") == null ? null : new BigDecimal((String) m.get("price")),
                    ((Number) m.get("totalQty")).longValue(),
                    OrderStatus.NEW);
            long filled = ((Number) m.get("filledQty")).longValue();
            if (filled > 0) {
                o.recordFill(filled);
            }
            Object canceledRaw = m.get("canceledQty");
            long canceled = canceledRaw == null ? 0L : ((Number) canceledRaw).longValue();
            if (canceled > 0) {
                o.recordCancel(canceled);
            }
            o.setStatus(OrderStatus.valueOf((String) m.get("status")));
            list.add(o);
        }
        return list;
    }

    private void restoreTrades(@SuppressWarnings("unchecked") Snapshot s) {
        Object raw = s.trades;
        if (!(raw instanceof Map<?, ?> wrapper)) {
            return;
        }
        Object listObj = ((Map<String, Object>) wrapper).get("list");
        if (!(listObj instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) item;
            trades.add(new Trade(
                    ((Number) m.get("seq")).longValue(),
                    (String) m.get("symbol"),
                    ((Number) m.get("tradeId")).longValue(),
                    (String) m.get("takerClientOrderId"),
                    (String) m.get("makerClientOrderId"),
                    (String) m.get("buyerAccountId"),
                    (String) m.get("sellerAccountId"),
                    Side.valueOf((String) m.get("takerSide")),
                    new BigDecimal((String) m.get("price")),
                    ((Number) m.get("quantity")).longValue()));
        }
    }

    private void restoreRejections(Snapshot s) {
        if (s.rejections == null) {
            return;
        }
        for (Object raw : s.rejections) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) raw;
            rejections.add(new Rejection(
                    ((Number) m.get("seq")).longValue(),
                    (String) m.get("clientOrderId"),
                    RejectReason.valueOf((String) m.get("reason")),
                    (String) m.get("detail")));
        }
    }
}
