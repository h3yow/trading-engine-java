package com.github.h3yow.tradingenginejava.matching.model;

import java.util.List;

/**
 * 委托组（原子批次）提交结果（不可变）。
 *
 * @param committed       整组是否落地：true=组内指令按序全部成功；false=整组回滚、零生效
 * @param firstSeq        批次声明的首条 seq（批次级拒绝时仍可用于定位）
 * @param results         committed=true 时组内每条指令各自的撮合结果（按 seq 升序）；否则为空
 * @param batchRejection  批次级拒绝原因（整组没落地），committed=true 时为 null
 * @param memberRejection 组内某条指令自身被拒（整组连带回滚），committed=true 时为 null
 */
public record BatchResult(
        boolean committed,
        long firstSeq,
        List<CommandResult> results,
        Rejection batchRejection,
        Rejection memberRejection
) {
    public BatchResult {
        results = results == null ? List.of() : List.copyOf(results);
    }

    /** 整组成功：每条指令各自的撮合结果可单独查询。 */
    public static BatchResult committed(long firstSeq, List<CommandResult> results) {
        return new BatchResult(true, firstSeq, results, null, null);
    }

    /** 整组回滚未落地：区分“批次本身非法”与“组内某条被拒”。 */
    public static BatchResult rolledBack(long firstSeq, Rejection batchRejection,
                                        Rejection memberRejection) {
        return new BatchResult(false, firstSeq, List.of(), batchRejection, memberRejection);
    }
}
