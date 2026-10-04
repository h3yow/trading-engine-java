package com.github.h3yow.tradingenginejava.matching.model;

import java.math.BigDecimal;

/**
 * 账户资产（仅允许撮合引擎包内修改）。
 *
 * <p>恒等不变式：
 * <ul>
 *   <li>cash = availableCash + reservedCash</li>
 *   <li>position = availablePosition + reservedPosition</li>
 * </ul>
 * 其中 cash/position 为总量；reserved* 是在途挂单冻结部分；四个分量恒非负。
 * 买入成交：买方 reservedCash -&gt; 卖方 availableCash，买方 availablePosition 增加；
 * 卖出成交：卖方 reservedPosition 直接交割，买方现金按对应冻结扣减。
 */
public final class Account {

    private final String accountId;
    private BigDecimal cash;
    private BigDecimal reservedCash = BigDecimal.ZERO;
    private long position;
    private long reservedPosition;

    public Account(String accountId, BigDecimal cash, long position) {
        this.accountId = accountId;
        this.cash = cash;
        this.position = position;
    }

    public String getAccountId() { return accountId; }
    /** 现金总量（含冻结）。 */
    public BigDecimal getCash() { return cash; }
    public BigDecimal getReservedCash() { return reservedCash; }
    /** 可用现金 = 现金总量 - 买入冻结。 */
    public BigDecimal getAvailableCash() { return cash.subtract(reservedCash); }
    /** 持仓总量（含冻结）。 */
    public long getPosition() { return position; }
    public long getReservedPosition() { return reservedPosition; }
    /** 可用持仓 = 持仓总量 - 卖出冻结。 */
    public long getAvailablePosition() { return position - reservedPosition; }

    /** 冻结买入资金；可用现金不足时抛 IllegalStateException（引擎应在前置校验拒绝，不应走到这里）。 */
    public void reserveCash(BigDecimal amount) {
        if (amount.signum() < 0 || getAvailableCash().compareTo(amount) < 0) {
            throw new IllegalStateException(accountId + " 现金不足，无法冻结 " + amount
                    + "，可用=" + getAvailableCash());
        }
        reservedCash = reservedCash.add(amount);
    }

    /** 解冻买入资金（撤单/改单替换时）。 */
    public void releaseCash(BigDecimal amount) {
        if (amount.signum() < 0 || reservedCash.compareTo(amount) < 0) {
            throw new IllegalStateException(accountId + " 解冻现金超过冻结额 " + amount
                    + "，冻结=" + reservedCash);
        }
        reservedCash = reservedCash.subtract(amount);
    }

    public void reservePosition(long qty) {
        if (qty < 0 || getAvailablePosition() < qty) {
            throw new IllegalStateException(accountId + " 持仓不足，无法冻结 " + qty
                    + "，可用=" + getAvailablePosition());
        }
        reservedPosition += qty;
    }

    public void releasePosition(long qty) {
        if (qty < 0 || reservedPosition < qty) {
            throw new IllegalStateException(accountId + " 解冻持仓超过冻结额 " + qty
                    + "，冻结=" + reservedPosition);
        }
        reservedPosition -= qty;
    }

    /**
     * 结算一笔成交。
     *
     * @param side  本账户在该笔成交中的方向
     * @param qty   成交量
     * @param cost  成交金额（price*qty，由引擎计算以避免重复）
     * @param wasReserved 该笔交割是否来自<b>已冻结</b>额度：
     *                    挂簿单成交（含改单重建单）传 true，从冻结现金/冻结持仓中扣减；
     *                    新到达即成交的市价/穿价限价单传 false，买方从可用现金扣、
     *                    卖方直接交割可用持仓（可用与总量同步减少，不动冻结分量）
     */
    public void settle(Side side, long qty, BigDecimal cost, boolean wasReserved) {
        if (side == Side.BUY) {
            if (wasReserved) {
                if (reservedCash.compareTo(cost) < 0) {
                    throw new IllegalStateException(accountId + " 买入结算冻结现金不足 cost=" + cost);
                }
                reservedCash = reservedCash.subtract(cost);
            } else {
                if (getAvailableCash().compareTo(cost) < 0) {
                    throw new IllegalStateException(accountId + " 买入可用现金不足 cost=" + cost
                            + " 可用=" + getAvailableCash());
                }
            }
            cash = cash.subtract(cost);
            position += qty;
        } else {
            if (wasReserved) {
                if (reservedPosition < qty) {
                    throw new IllegalStateException(accountId + " 卖出结算冻结持仓不足 qty=" + qty
                            + " 冻结=" + reservedPosition);
                }
                reservedPosition -= qty;
            } else {
                // 新到达即成交的卖方（市价卖 / 穿价限价卖）：从可用持仓直接交割，不得超卖
                if (getAvailablePosition() < qty) {
                    throw new IllegalStateException(accountId + " 卖出可用持仓不足 qty=" + qty
                            + " 可用=" + getAvailablePosition());
                }
            }
            position -= qty;
            cash = cash.add(cost);
        }
    }

    @Override
    public String toString() {
        return "Account[" + accountId + " cash=" + cash + "(reserved=" + reservedCash
                + ",available=" + getAvailableCash() + ") position=" + position
                + "(reserved=" + reservedPosition + ",available=" + getAvailablePosition() + ")]";
    }
}
