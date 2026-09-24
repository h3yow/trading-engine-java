package com.github.h3yow.tradingenginejava.account;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单账户资产账本：现金 + 多品种持仓，均区分可用与冻结（预留）。
 *
 * 不变式（任何时候都成立，由调用方在撮合引擎单锁内保证）：
 *   cash >= reservedCash >= 0
 *   position(symbol) >= reservedPosition(symbol) >= 0
 *
 * 买单：限价单按下单金额 price*qty 预冻现金，成交时按实际成交额扣款，
 *       余量在成交结束 / 撤单时解冻；市价单不预冻，逐档检查可用现金。
 * 卖单：按下单数量预冻持仓，成交逐笔核销，余量在成交结束 / 撤单时解冻。
 */
public final class Account {

    private final String accountId;
    private long cash;
    private long reservedCash;
    private final Map<String, Long> positions = new LinkedHashMap<>();
    private final Map<String, Long> reservedPositions = new LinkedHashMap<>();

    public Account(String accountId) {
        this.accountId = accountId;
    }

    public String getAccountId() { return accountId; }
    public long getCash() { return cash; }
    public long getReservedCash() { return reservedCash; }
    public long getAvailableCash() { return cash - reservedCash; }

    public long getPosition(String symbol) { return positions.getOrDefault(symbol, 0L); }
    public long getReservedPosition(String symbol) { return reservedPositions.getOrDefault(symbol, 0L); }
    public long getAvailablePosition(String symbol) {
        return getPosition(symbol) - getReservedPosition(symbol);
    }

    public Map<String, Long> getPositions() { return positions; }
    public Map<String, Long> getReservedPositions() { return reservedPositions; }

    /** 入金 / 划入持仓（DEPOSIT 命令）。 */
    public void deposit(long cashAmount, String symbol, long qty) {
        if (cashAmount > 0) {
            cash += cashAmount;
        }
        if (symbol != null && qty > 0) {
            positions.merge(symbol, qty, Long::sum);
        }
    }

    /** 冻结现金（限价买入下单），可用不足返回 false。 */
    public boolean reserveCash(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("reserved amount must be non-negative: " + amount);
        }
        if (amount > getAvailableCash()) {
            return false;
        }
        reservedCash += amount;
        return true;
    }

    public void releaseCash(long amount) {
        if (amount == 0) {
            return;
        }
        if (amount < 0 || amount > reservedCash) {
            throw new IllegalStateException(
                    "cannot release " + amount + " reserved cash, current reserved=" + reservedCash);
        }
        reservedCash -= amount;
    }

    /** 冻结持仓（卖出下单），可用不足返回 false。 */
    public boolean reservePosition(String symbol, long qty) {
        if (qty < 0) {
            throw new IllegalArgumentException("reserved qty must be non-negative: " + qty);
        }
        if (qty > getAvailablePosition(symbol)) {
            return false;
        }
        reservedPositions.merge(symbol, qty, Long::sum);
        return true;
    }

    public void releasePosition(String symbol, long qty) {
        if (qty == 0) {
            return;
        }
        long current = getReservedPosition(symbol);
        if (qty < 0 || qty > current) {
            throw new IllegalStateException(
                    "cannot release " + qty + " reserved position of " + symbol + ", current=" + current);
        }
        if (qty == current) {
            reservedPositions.remove(symbol);
        } else {
            reservedPositions.put(symbol, current - qty);
        }
    }

    /**
     * 买方成交结算：支付成交额 cost，收入 qty 持仓；
     * 同步从冻结中核销 reservedCashToRelease（限价单 = limitPrice*本次成交量）。
     */
    public void settleBuy(String symbol, long qty, long cost, long reservedCashToRelease) {
        if (reservedCashToRelease > 0) {
            releaseCash(reservedCashToRelease);
        }
        if (cost < 0 || cost > cash) {
            throw new IllegalStateException(
                    "buy settlement would make cash negative: cost=" + cost + ", cash=" + cash);
        }
        cash -= cost;
        positions.merge(symbol, qty, Long::sum);
    }

    /**
     * 卖方成交结算：从冻结持仓中核销 qty，收入成交额 proceeds；
     * reservedPositionToRelease 为本次成交对应解冻的冻结量（限价/市价卖单均为成交量）。
     */
    public void settleSell(String symbol, long qty, long proceeds, long reservedPositionToRelease) {
        if (reservedPositionToRelease > 0) {
            releasePosition(symbol, reservedPositionToRelease);
        }
        long current = getPosition(symbol);
        if (qty < 0 || qty > current) {
            throw new IllegalStateException(
                    "sell settlement would make position negative: qty=" + qty + ", pos=" + current);
        }
        if (qty == current) {
            positions.remove(symbol);
        } else {
            positions.put(symbol, current - qty);
        }
        cash += proceeds;
    }
}
