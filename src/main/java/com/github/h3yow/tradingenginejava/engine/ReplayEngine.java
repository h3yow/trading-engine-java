package com.github.h3yow.tradingenginejava.engine;

import com.github.h3yow.tradingenginejava.command.Command;
import com.github.h3yow.tradingenginejava.command.CommandResult;

import java.util.List;

/**
 * 确定性回放：给定空引擎 + 命令日志，重新执行得到结果与快照；
 * 也可从某一快照恢复后继续回放增量命令。
 *
 * 保证：
 *  - 同一份命令日志重复回放，成交序列、余额、快照完全一致（可复现）；
 *  - 从快照恢复只载入状态、不重放历史成交，因此不会产生重复成交；
 *  - 恢复后继续执行的命令序号必须大于快照已执行序号（否则 INVALID_SEQUENCE）。
 */
public final class ReplayEngine {

    private ReplayEngine() {
    }

    public record ReplayOutcome(List<CommandResult> results, EngineSnapshot snapshot) {
    }

    /** 从空状态完整回放。 */
    public static ReplayOutcome replay(List<Command> commands) {
        MatchingEngine engine = new MatchingEngine();
        List<CommandResult> results = engine.submitBatch(commands);
        return new ReplayOutcome(results, engine.snapshot());
    }

    /** 从快照恢复后回放增量命令（恢复不产生任何成交）。 */
    public static ReplayOutcome replayFrom(EngineSnapshot snapshot, List<Command> commands) {
        MatchingEngine engine = MatchingEngine.restore(snapshot);
        List<CommandResult> results = engine.submitBatch(commands);
        return new ReplayOutcome(results, engine.snapshot());
    }
}
