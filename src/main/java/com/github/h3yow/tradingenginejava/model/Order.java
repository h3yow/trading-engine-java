package com.github.h3yow.tradingenginejava.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 订单（撮合期间部分字段可变，撮合在引擎单锁内完成）。
 * 带全参构造器供快照 JSON 反序列化。
 */
public final class Order {
    private final String orderId;
    private final String accountId;
    private final String symbol;
    private final Side side;
    private OrderType type;
    private long price;
    private long originalQty;
    private long filledQty;
    private OrderStatus status;
    private long seq;
    private RejectReason rejectReason;

    @JsonCreator
    public Order(@JsonProperty("orderId") String orderId,
                 @JsonProperty("accountId") String accountId,
                 @JsonProperty("symbol") String symbol,
                 @JsonProperty("side") Side side,
                 @JsonProperty("type") OrderType type,
                 @JsonProperty("price") long price,
                 @JsonProperty("originalQty") long originalQty,
                 @JsonProperty("seq") long seq) {
        this.orderId = orderId;
        this.accountId = accountId;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.price = price;
        this.originalQty = originalQty;
        this.filledQty = 0L;
        this.status = OrderStatus.NEW;
        this.seq = seq;
    }

    public String getOrderId() { return orderId; }
    public String getAccountId() { return accountId; }
    public String getSymbol() { return symbol; }
    public Side getSide() { return side; }
    public OrderType getType() { return type; }
    public long getPrice() { return price; }
    public long getOriginalQty() { return originalQty; }
    public long getFilledQty() { return filledQty; }
    public OrderStatus getStatus() { return status; }
    public long getSeq() { return seq; }
    public RejectReason getRejectReason() { return rejectReason; }

    public long getRemainingQty() { return originalQty - filledQty; }

    public void setType(OrderType type) { this.type = type; }
    public void setPrice(long price) { this.price = price; }
    public void setOriginalQty(long qty) { this.originalQty = qty; }
    public void setFilledQty(long qty) { this.filledQty = qty; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public void setSeq(long seq) { this.seq = seq; }
    public void setRejectReason(RejectReason reason) { this.rejectReason = reason; }
}
