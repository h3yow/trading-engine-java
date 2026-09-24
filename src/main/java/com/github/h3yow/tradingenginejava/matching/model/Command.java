package com.github.h3yow.tradingenginejava.matching.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 确定性撮合指令（不可变值对象）。
 *
 * <p>seq 为全序序号：同一批指令无论以何种并发时序到达网关，都严格按 seq 升序进入撮合引擎，
 * 从而保证成交序列完全由输入决定，与线程调度无关。clientOrderId 为客户端幂等键，
 * 在“下单”维度全局唯一；撤单/改单通过它引用既有委托。
 */
public final class Command {

    private final long seq;
    private final CommandType type;
    private final String accountId;
    private final String symbol;
    private final String clientOrderId;

    // ---- PLACE 字段 ----
    private final Side side;
    private final OrderType orderType;
    private final BigDecimal price; // 限价单价；市价单为 null
    private final long quantity;

    // ---- REPLACE 字段：改单 = 撤旧 + 按剩余量约束下新单（丢失时间优先） ----
    private final BigDecimal newPrice;
    private final Long newQuantity;

    private Command(long seq, CommandType type, String accountId, String symbol,
                    String clientOrderId, Side side, OrderType orderType,
                    BigDecimal price, long quantity,
                    BigDecimal newPrice, Long newQuantity) {
        this.seq = seq;
        this.type = type;
        this.accountId = accountId;
        this.symbol = symbol;
        this.clientOrderId = clientOrderId;
        this.side = side;
        this.orderType = orderType;
        this.price = price;
        this.quantity = quantity;
        this.newPrice = newPrice;
        this.newQuantity = newQuantity;
    }

    /** 下限价单。 */
    public static Command placeLimit(long seq, String accountId, String symbol,
                                     String clientOrderId, Side side,
                                     BigDecimal price, long quantity) {
        return new Command(seq, CommandType.PLACE, accountId, symbol, clientOrderId,
                side, OrderType.LIMIT, Objects.requireNonNull(price, "price"), quantity,
                null, null);
    }

    /** 下市价单（IOC：按对手挂单立即成交，未成交余量撤销，不进入订单簿）。 */
    public static Command placeMarket(long seq, String accountId, String symbol,
                                      String clientOrderId, Side side, long quantity) {
        return new Command(seq, CommandType.PLACE, accountId, symbol, clientOrderId,
                side, OrderType.MARKET, null, quantity, null, null);
    }

    /** 撤单。 */
    public static Command cancel(long seq, String accountId, String clientOrderId) {
        return new Command(seq, CommandType.CANCEL, accountId, null, clientOrderId,
                null, null, null, 0L, null, null);
    }

    /** 改单（仅限限价单）：newPrice 为新限价，newQuantity 为新的委托总量（须大于已成交量）。 */
    public static Command replace(long seq, String accountId, String clientOrderId,
                                  BigDecimal newPrice, long newQuantity) {
        return new Command(seq, CommandType.REPLACE, accountId, null, clientOrderId,
                null, null, null, 0L,
                Objects.requireNonNull(newPrice, "newPrice"), newQuantity);
    }

    public long getSeq() { return seq; }
    public CommandType getType() { return type; }
    public String getAccountId() { return accountId; }
    public String getSymbol() { return symbol; }
    public String getClientOrderId() { return clientOrderId; }
    public Side getSide() { return side; }
    public OrderType getOrderType() { return orderType; }
    public BigDecimal getPrice() { return price; }
    public long getQuantity() { return quantity; }
    public BigDecimal getNewPrice() { return newPrice; }
    public Long getNewQuantity() { return newQuantity; }

    @Override
    public String toString() {
        return switch (type) {
            case PLACE -> "Command#" + seq + " PLACE " + accountId + " " + side + " "
                    + orderType + (price != null ? "@" + price : "") + " x" + quantity
                    + " " + symbol + " id=" + clientOrderId;
            case CANCEL -> "Command#" + seq + " CANCEL " + accountId + " id=" + clientOrderId;
            case REPLACE -> "Command#" + seq + " REPLACE " + accountId + " id=" + clientOrderId
                    + " -> @" + newPrice + " x" + newQuantity;
        };
    }
}
