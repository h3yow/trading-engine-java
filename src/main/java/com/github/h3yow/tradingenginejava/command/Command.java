package com.github.h3yow.tradingenginejava.command;

import com.github.h3yow.tradingenginejava.model.CommandType;
import com.github.h3yow.tradingenginejava.model.OrderType;
import com.github.h3yow.tradingenginejava.model.Side;

/**
 * 撮合命令。确定性排序键为 seq：同一批命令无论并发到达顺序如何，
 * 引擎都按 seq 升序串行执行，因此成交序列与余额只取决于输入。
 */
public record Command(
        long seq,
        CommandType type,
        String accountId,
        String orderId,
        String symbol,
        Side side,
        OrderType orderType,
        long price,
        long qty,
        long depositCash,
        long depositQty
) {
    public static Command deposit(long seq, String accountId, long cash, String symbol, long qty) {
        return new Command(seq, CommandType.DEPOSIT, accountId, null,
                symbol, null, null, 0L, 0L, cash, qty);
    }

    public static Command place(long seq, String accountId, String orderId, String symbol,
                                Side side, OrderType orderType, long price, long qty) {
        return new Command(seq, CommandType.PLACE, accountId, orderId, symbol,
                side, orderType, price, qty, 0L, 0L);
    }

    public static Command cancel(long seq, String accountId, String orderId) {
        return new Command(seq, CommandType.CANCEL, accountId, orderId, null,
                null, null, 0L, 0L, 0L, 0L);
    }

    public static Command modify(long seq, String accountId, String orderId,
                                 OrderType orderType, long price, long qty) {
        return new Command(seq, CommandType.MODIFY, accountId, orderId, null,
                null, orderType, price, qty, 0L, 0L);
    }
}
