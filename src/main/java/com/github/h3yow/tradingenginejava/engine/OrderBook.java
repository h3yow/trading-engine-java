package com.github.h3yow.tradingenginejava.engine;

import com.github.h3yow.tradingenginejava.model.Order;
import com.github.h3yow.tradingenginejava.model.OrderType;
import com.github.h3yow.tradingenginejava.model.Side;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 单品种订单簿：价格优先、同价时间优先（seq 升序）。
 *
 * 买方向按价格降序；卖方向按价格升序。同价位内部以 seq 升序（TreeMap 的 key 同时包含
 * price 与 seq），因此 bestXxx() 取出的第一个对手单就是撮合规则下的下一个被动单。
 *
 * 仅在 MatchingEngine 的单一锁内访问，不自行加锁。
 */
final class OrderBook {

    /** 同价位内部再按时间（seq）排序的复合键。 */
    private record LevelKey(long price, long seq) {
    }

    private static final Comparator<LevelKey> BID_ORDER =
            Comparator.comparingLong(LevelKey::price).reversed()
                    .thenComparingLong(LevelKey::seq);
    private static final Comparator<LevelKey> ASK_ORDER =
            Comparator.comparingLong(LevelKey::price)
                    .thenComparingLong(LevelKey::seq);

    private final String symbol;
    private final TreeMap<LevelKey, Order> bids = new TreeMap<>(BID_ORDER);
    private final TreeMap<LevelKey, Order> asks = new TreeMap<>(ASK_ORDER);

    OrderBook(String symbol) {
        this.symbol = symbol;
    }

    String getSymbol() {
        return symbol;
    }

    private static LevelKey key(Order o) {
        return new LevelKey(o.getPrice(), o.getSeq());
    }

    /** 新增或改单后重新插入（改单会以新 seq 排到同价位队尾，失去原时间优先级）。 */
    void add(Order order) {
        if (order.getSide() == Side.BUY) {
            bids.put(key(order), order);
        } else {
            asks.put(key(order), order);
        }
    }

    void remove(Order order) {
        if (order.getSide() == Side.BUY) {
            bids.remove(key(order));
        } else {
            asks.remove(key(order));
        }
    }

    /** 卖方吃单时的最优买价（最高买价、最早挂单）。 */
    Order bestBid() {
        return bids.isEmpty() ? null : bids.firstEntry().getValue();
    }

    /** 买方吃单时的最优卖价（最低卖价、最早挂单）。 */
    Order bestAsk() {
        return asks.isEmpty() ? null : asks.firstEntry().getValue();
    }

    long bestBidPrice() {
        return bids.isEmpty() ? Long.MAX_VALUE : bids.firstKey().price();
    }

    long bestAskPrice() {
        return asks.isEmpty() ? Long.MIN_VALUE : asks.firstKey().price();
    }

    boolean isEmpty() {
        return bids.isEmpty() && asks.isEmpty();
    }

    List<Order> activeOrders() {
        List<Order> all = new ArrayList<>(bids.size() + asks.size());
        all.addAll(bids.values());
        all.addAll(asks.values());
        return List.copyOf(all);
    }

    /**
     * 自成交预检：若 taker 立即撮合会命中同账户的对手单，返回 true。
     * 规则：自成交委托在吃单前整体拒绝（SELF_TRADE），不允许与自己部分成交后再取消。
     *
     * @param takerPrice 限价单为限价；市价单调用方传 0（表示不设价格门槛）
     */
    boolean hasCounterOrderOfAccount(Side takerSide, long takerPrice,
                                     boolean takerIsMarket, String accountId) {
        NavigableMap<LevelKey, Order> counter = takerSide == Side.BUY ? asks : bids;
        for (Order o : counter.values()) {
            if (!o.getAccountId().equals(accountId)) {
                continue;
            }
            if (takerIsMarket) {
                return true; // 市价单吃所有档位，遇到同账户对手单必然自成交
            }
            boolean crosses = takerSide == Side.BUY
                    ? takerPrice >= o.getPrice()
                    : takerPrice <= o.getPrice();
            if (crosses) {
                return true;
            }
        }
        return false;
    }
}
