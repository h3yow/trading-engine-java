package com.github.h3yow.tradingenginejava.matching.model;

import java.util.List;

/**
 * 单条指令的处理结果（不可变）。
 *
 * <p>{@code accepted=false} 时 {@link #rejection()} 给出可区分的拒绝原因，委托从未进入订单簿；
 * {@code trades} 为该指令直接触发的有序成交序列，便于逐条核对“输入与判定依据”。
 * {@code remainingQty} 为指令处理完后该委托的剩余在簿量（撤单/拒绝为 0）。
 */
public record CommandResult(
        long seq,
        boolean accepted,
        String clientOrderId,
        OrderStatus finalStatus,
        long remainingQty,
        List<Trade> trades,
        Rejection rejection
) {

    public static CommandResult accepted(long seq, String clientOrderId,
                                         OrderStatus finalStatus, long remainingQty,
                                         List<Trade> trades) {
        return new CommandResult(seq, true, clientOrderId, finalStatus, remainingQty,
                List.copyOf(trades), null);
    }

    public static CommandResult rejected(Rejection rejection) {
        return new CommandResult(rejection.seq(), false, rejection.clientOrderId(),
                OrderStatus.REJECTED, 0L, List.of(), rejection);
    }

    @Override
    public String toString() {
        if (!accepted) {
            return "Result#" + seq + " REJECTED id=" + clientOrderId
                    + " reason=" + (rejection == null ? "?" : rejection.reason())
                    + " detail=" + (rejection == null ? "?" : rejection.detail());
        }
        return "Result#" + seq + " ACCEPTED id=" + clientOrderId
                + " status=" + finalStatus + " remaining=" + remainingQty
                + " trades=" + trades.size();
    }
}
