package com.github.h3yow.tradingenginejava.matching.engine;

import com.github.h3yow.tradingenginejava.matching.model.Order;
import com.github.h3yow.tradingenginejava.matching.model.Side;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 单标的订单簿（仅引擎同包使用，单线程访问，不加锁）。
 *
 * <p>价格优先：买单按价格从高到低、卖单按价格从低到高；
 * 时间优先：同一价位上 {@code TreeMap} 价位桶内的 FIFO 队列，先到先成交。
 * 数据结构：{@code TreeMap<价格, 同价位 FIFO 队列>}。
 */
final class OrderBook {

    private final String symbol;
    // 买盘：价格自然降序消费 -> 直接 descendingMap；卖盘：自然升序
    private final NavigableMap<BigDecimal, List<Order>> bids = new TreeMap<>(BigDecimal::compareTo);
    private final NavigableMap<BigDecimal, List<Order>> asks = new TreeMap<>(BigDecimal::compareTo);

    OrderBook(String symbol) {
        this.symbol = symbol;
    }

    String getSymbol() {
        return symbol;
    }

    /** 与 taker 方向相反的一侧（吃单时扫描）。 */
    private NavigableMap<BigDecimal, List<Order>> opposite(Side takerSide) {
        return takerSide == Side.BUY ? asks : bids;
    }

    /** 最优对手价；无挂单返回 null。 */
    BigDecimal bestOppositePrice(Side takerSide) {
        NavigableMap<BigDecimal, List<Order>> book = opposite(takerSide);
        if (book.isEmpty()) {
            return null;
        }
        return takerSide == Side.BUY ? book.firstKey() : book.lastKey();
    }

    /** 从最优价位开始迭代对手挂单（价格优先、同价时间优先），自动跳过已终结委托。 */
    Iterator<Order> oppositeIterator(Side takerSide) {
        NavigableMap<BigDecimal, List<Order>> book = opposite(takerSide);
        Iterable<BigDecimal> prices = takerSide == Side.BUY
                ? book.navigableKeySet()
                : book.descendingKeySet();
        List<Iterator<Order>> levelIterators = new ArrayList<>();
        for (BigDecimal price : prices) {
            levelIterators.add(book.get(price).iterator());
        }
        return new Iterator<>() {
            private int level = 0;

            @Override
            public boolean hasNext() {
                while (level < levelIterators.size()) {
                    if (levelIterators.get(level).hasNext()) {
                        return true;
                    }
                    level++;
                }
                return false;
            }

            @Override
            public Order next() {
                if (!hasNext()) {
                    throw new java.util.NoSuchElementException();
                }
                return levelIterators.get(level).next();
            }
        };
    }

    /**
     * 自成交预检：对手盘从优到劣、在 taker 可接受的价位范围内（limitPrice 为 null 表示市价不限），
     * 是否存在同账户 live 挂单。
     */
    boolean hasSameAccountOnOpposite(Side takerSide, String accountId, BigDecimal limitPrice) {
        for (Iterator<Order> it = oppositeIterator(takerSide); it.hasNext(); ) {
            Order maker = it.next();
            if (!maker.isLive()) {
                continue;
            }
            if (limitPrice != null) {
                int cmp = takerSide == Side.BUY
                        ? limitPrice.compareTo(maker.getPrice())
                        : maker.getPrice().compareTo(limitPrice);
                if (cmp < 0) {
                    break; // 已越过可成交价位，更差价位无需再看
                }
            }
            if (maker.getAccountId().equals(accountId)) {
                return true;
            }
        }
        return false;
    }

    /** 将未成交完的限价余量挂入本侧订单簿尾部（时间优先由插入顺序体现）。 */
    void add(Order order) {
        sideOf(order.getSide())
                .computeIfAbsent(order.getPrice(), p -> new ArrayList<>())
                .add(order);
    }

    private NavigableMap<BigDecimal, List<Order>> sideOf(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    /** 物理移除指定委托并清理空价位桶（改单撤旧使用）。 */
    void remove(Order order) {
        NavigableMap<BigDecimal, List<Order>> side = sideOf(order.getSide());
        List<Order> level = side.get(order.getPrice());
        if (level != null) {
            level.removeIf(o -> o.getId() == order.getId());
            if (level.isEmpty()) {
                side.remove(order.getPrice());
            }
        }
    }

    /** 物理清理所有已终结委托（FILLED/CANCELED/REJECTED）与空价位桶。 */
    void prune() {
        pruneSide(bids);
        pruneSide(asks);
    }

    private void pruneSide(NavigableMap<BigDecimal, List<Order>> side) {
        side.values().removeIf(level -> {
            level.removeIf(o -> !o.isLive());
            return level.isEmpty();
        });
    }

    boolean isEmpty() {
        return bids.isEmpty() && asks.isEmpty();
    }

    /**
     * 按簿内顺序导出全部 live 委托（买盘价格降序、卖盘升序，价位内 FIFO）。
     * 委托组回滚备忘录据此重建订单簿，保证价位内排队顺序逐位一致。
     */
    List<Order> liveOrdersInBookOrder() {
        List<Order> out = new ArrayList<>();
        for (List<Order> level : bids.descendingMap().values()) {
            for (Order o : level) {
                if (o.isLive()) {
                    out.add(o);
                }
            }
        }
        for (List<Order> level : asks.values()) {
            for (Order o : level) {
                if (o.isLive()) {
                    out.add(o);
                }
            }
        }
        return out;
    }

    /** 当前在簿 live 委托数（风控用）。 */
    int liveOrderCount(String accountId) {
        int n = 0;
        for (List<Order> level : bids.values()) {
            for (Order o : level) {
                if (o.isLive() && o.getAccountId().equals(accountId)) {
                    n++;
                }
            }
        }
        for (List<Order> level : asks.values()) {
            for (Order o : level) {
                if (o.isLive() && o.getAccountId().equals(accountId)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 导出确定性快照：价位降序列出买盘、升序列出卖盘，价位内按 FIFO。 */
    Map<String, Object> snapshot() {
        Map<String, Object> book = new LinkedHashMap<>();
        book.put("symbol", symbol);
        book.put("bids", levelsSnapshot(bids.descendingMap()));
        book.put("asks", levelsSnapshot(asks));
        return book;
    }

    private List<Map<String, Object>> levelsSnapshot(NavigableMap<BigDecimal, List<Order>> side) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<BigDecimal, List<Order>> e : side.entrySet()) {
            List<Map<String, Object>> orders = new ArrayList<>();
            long levelQty = 0L;
            for (Order o : e.getValue()) {
                if (!o.isLive()) {
                    continue;
                }
                levelQty += o.getOpenQty();
                orders.add(orderSnapshot(o));
            }
            if (levelQty == 0L) {
                continue;
            }
            Map<String, Object> level = new LinkedHashMap<>();
            level.put("price", e.getKey().toPlainString());
            level.put("openQty", levelQty);
            level.put("orders", orders);
            out.add(level);
        }
        return out;
    }

    private Map<String, Object> orderSnapshot(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", o.getSeq());
        m.put("id", o.getId());
        m.put("clientOrderId", o.getClientOrderId());
        m.put("accountId", o.getAccountId());
        m.put("side", o.getSide().name());
        m.put("type", o.getType().name());
        m.put("price", o.getPrice() == null ? null : o.getPrice().toPlainString());
        m.put("totalQty", o.getTotalQty());
        m.put("filledQty", o.getFilledQty());
        m.put("openQty", o.getOpenQty());
        m.put("status", o.getStatus().name());
        return m;
    }
}
