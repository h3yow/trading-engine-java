package com.github.h3yow.tradingenginejava.matching.model;

import java.math.BigDecimal;

/**
 * 订单簿中的委托（可变状态对象，仅允许撮合引擎单线程修改）。
 *
 * <p>价格用 {@link BigDecimal}，数量为正整数。{@code filledQty} 累计已成交量；
 * {@code openQty} 为仍在簿（或本次吃单仍待成交）的数量。限价单在簿时冻结买方现金/卖方持仓，
 * 成交或撤单时逐量解冻，保证账户四个分量恒非负。
 */
public final class Order {

    private final long id;
    private final long seq; // 下单指令序号，决定同价位时间优先
    private final String clientOrderId;
    private final String accountId;
    private final String symbol;
    private final Side side;
    private final OrderType type;
    private BigDecimal price; // 市价单为 null；改单时更新
    private long totalQty;    // 改单时更新（新委托总量，filledQty 保留）

    private long filledQty;
    private long canceledQty;
    private OrderStatus status;
    /** 改单重建后、撮合前是否已为全部 openQty 冻结资金/持仓。 */
    private boolean preReserved;

    public Order(long id, long seq, String clientOrderId, String accountId, String symbol,
                 Side side, OrderType type, BigDecimal price, long totalQty, OrderStatus status) {
        this.id = id;
        this.seq = seq;
        this.clientOrderId = clientOrderId;
        this.accountId = accountId;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.price = price;
        this.totalQty = totalQty;
        this.filledQty = 0L;
        this.status = status;
    }

    public long getId() { return id; }
    public long getSeq() { return seq; }
    public String getClientOrderId() { return clientOrderId; }
    public String getAccountId() { return accountId; }
    public String getSymbol() { return symbol; }
    public Side getSide() { return side; }
    public OrderType getType() { return type; }
    public BigDecimal getPrice() { return price; }
    public long getTotalQty() { return totalQty; }
    public long getFilledQty() { return filledQty; }
    public long getCanceledQty() { return canceledQty; }
    /** 仍有效待成交的量：total - filled - canceled。 */
    public long getOpenQty() { return totalQty - filledQty - canceledQty; }
    public OrderStatus getStatus() { return status; }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    /** 登记一次成交，filledQty 恰好等于 totalQty 时自动置为 FILLED，否则 PARTIALLY_FILLED。 */
    public void recordFill(long qty) {
        if (qty <= 0 || qty > getOpenQty()) {
            throw new IllegalArgumentException("非法成交量 qty=" + qty + " on " + clientOrderId);
        }
        filledQty += qty;
        status = (filledQty == totalQty) ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    /**
     * 改单重建：保留 id 与已成交历史，更新委托总量与价格，状态回到 NEW（尚有 openQty）。
     * 改单后的委托作为新到达委托重新参与撮合/排队（丢失原时间优先）。
     */
    public void rebuildForReplace(BigDecimal newPrice, long newTotalQty) {
        if (newPrice == null || newPrice.signum() <= 0) {
            throw new IllegalArgumentException("改单价格非法: " + newPrice);
        }
        if (newTotalQty <= filledQty) {
            throw new IllegalArgumentException("改单总量须大于已成交量");
        }
        this.price = newPrice;
        this.totalQty = newTotalQty;
        this.status = OrderStatus.NEW;
        this.preReserved = false;
    }

    /** IOC 余量撤销或撤单时登记；登记后 openQty 归零，filled+canceled=total。 */
    public void recordCancel(long qty) {
        if (qty < 0 || qty > getOpenQty()) {
            throw new IllegalArgumentException("非法撤销量 qty=" + qty + " on " + clientOrderId);
        }
        canceledQty += qty;
        status = OrderStatus.CANCELED;
    }

    /** 是否仍可成交（挂在簿上或本次吃单进行中）。 */
    public boolean isLive() {
        return status == OrderStatus.NEW || status == OrderStatus.PARTIALLY_FILLED;
    }

    public boolean isPreReserved() {
        return preReserved;
    }

    public void setPreReserved(boolean preReserved) {
        this.preReserved = preReserved;
    }

    @Override
    public String toString() {
        return "Order[" + clientOrderId + " " + accountId + " " + side + " " + type
                + (price != null ? "@" + price : "@MKT")
                + " total=" + totalQty + " filled=" + filledQty + " open=" + getOpenQty()
                + " " + status + "]";
    }
}
