package com.github.h3yow.tradingenginejava.matching.replay;

import com.github.h3yow.tradingenginejava.matching.engine.MatchingEngine;
import com.github.h3yow.tradingenginejava.matching.model.Command;
import com.github.h3yow.tradingenginejava.matching.model.CommandResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 确定性回放工具。
 *
 * <p>典型路径：空引擎先 {@code restore(snapshot)}（水位 lastSeq = k），
 * 再 {@code replay(engine, events)} 应用 k 之后的增量日志。
 * {@code seq <= lastSeq} 的指令视为已应用，<b>直接跳过</b>，既不重复成交，
 * 也不产生 DUPLICATE_COMMAND 拒绝污染；因此整段日志可重复回放任意次，
 * 最终状态与成交序列恒等（幂等）。
 */
public final class ReplaySupport {

    private ReplaySupport() {
    }

    /**
     * @return 本次回放真正新应用的指令结果（跳过的历史指令不在其中），按 seq 升序
     */
    public static List<CommandResult> replay(MatchingEngine engine, List<Command> log) {
        List<Command> sorted = new ArrayList<>(log);
        sorted.sort(Comparator.comparingLong(Command::getSeq));
        List<CommandResult> applied = new ArrayList<>();
        for (Command c : sorted) {
            if (c.getSeq() <= engine.getLastSeq()) {
                continue; // 已在快照或此前的回放中应用
            }
            applied.add(engine.apply(c));
        }
        return applied;
    }
}
