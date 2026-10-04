package com.github.h3yow.tradingenginejava.matching.model;

import java.util.List;

/**
 * 委托组原子提交的结果（不可变）。
 *
 * <p>{@code committed=true}：整批已按既定顺序逐条撮合生效，{@link #results()} 中每条
 * 委托各自的撮合结果可单独查询（与单条提交的 {@link CommandResult} 同构）。
 *
 * <p>{@code committed=false}：整批未落地，状态零残留（无成交、无委托、无冻结变动）。
 * 此时 {@link #results()} 仍逐条给出判定：
 * <ul>
 *   <li>本身不合法的那条指令保留其<b>自身拒绝原因</b>（如 INSUFFICIENT_CASH）；</li>
 *   <li>组内其余指令标记为 {@link RejectReason#BATCH_ROLLED_BACK}，
 *       表示“是整组没落地，不是这条本身的问题”。</li>
 * </ul>
 * 以此把“整批被拒”与“单条被拒”明确区分开。
 */
public record BatchResult(
        long startSeq,
        boolean committed,
        List<CommandResult> results
) {
    public BatchResult {
        results = List.copyOf(results);
    }

    /** 整批占用的指令条数（无论成败，这些 seq 均被消耗，保证事件日志连续）。 */
    public int size() {
        return results.size();
    }

    /** 触发整批回滚的那条指令的自身拒绝原因；committed 时为 null。 */
    public RejectReason failureReason() {
        if (committed) {
            return null;
        }
        return results.stream()
                .filter(r -> !r.accepted()
                        && r.rejection() != null
                        && r.rejection().reason() != RejectReason.BATCH_ROLLED_BACK)
                .map(r -> r.rejection().reason())
                .findFirst()
                .orElse(null);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("BatchResult#")
                .append(startSeq)
                .append(committed ? " COMMITTED" : " ROLLED_BACK")
                .append(" size=").append(results.size());
        if (!committed) {
            sb.append(" failure=").append(failureReason());
        }
        return sb.toString();
    }
}
