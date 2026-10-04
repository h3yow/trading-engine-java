package com.github.h3yow.tradingenginejava.matching.snapshot;

/**
 * 一致性快照导出物（不可变）。
 *
 * <p>{@code snapshot} 与 {@code json} 都严格落在撮合全序的一个明确点位
 * （恰好水位为 {@code lastSeq} 的指令边界：最后一条已应用指令与下一条待应用指令之间），
 * 不会夹在任何指令的处理中间。
 *
 * @param lastSeq 快照水位：快照包含且仅包含 seq ≤ lastSeq 的全部指令效果
 * @param snapshot 内存快照结构（可直接 restore）
 * @param json     与 snapshot 同一点位的确定性 JSON 文本（采集完成后在锁外序列化）
 * @param captureNanos 采集（持锁）耗时，用于评估对写入的影响
 */
public record SnapshotExport(
        long lastSeq,
        Snapshot snapshot,
        String json,
        long captureNanos
) {
}
