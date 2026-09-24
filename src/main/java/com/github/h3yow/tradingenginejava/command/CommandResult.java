package com.github.h3yow.tradingenginejava.command;

import com.github.h3yow.tradingenginejava.model.RejectReason;

/**
 * 单条命令的执行结果。
 * accepted=false 时 reason 给出可区分的拒绝原因，message 给出判定依据（可打印）。
 * tradeSeqs 为该命令产生的成交序号。
 */
public record CommandResult(
        long seq,
        boolean accepted,
        RejectReason reason,
        String message,
        long[] tradeSeqs
) {
    public static CommandResult ok(long seq, long[] trades, String message) {
        return new CommandResult(seq, true, null, message, trades);
    }

    public static CommandResult reject(long seq, RejectReason reason, String message) {
        return new CommandResult(seq, false, reason, message, new long[0]);
    }
}
