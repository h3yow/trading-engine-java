package com.github.h3yow.tradingenginejava.api;

import com.github.h3yow.tradingenginejava.command.Command;
import com.github.h3yow.tradingenginejava.command.CommandResult;
import com.github.h3yow.tradingenginejava.engine.EngineSnapshot;
import com.github.h3yow.tradingenginejava.engine.MatchingEngine;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 本地 HTTP 入口：提交命令 / 查询快照，便于手工验证撮合流程。 */
@RestController
@RequestMapping("/api")
public final class TradingController {

    private final MatchingEngine engine = new MatchingEngine();

    @PostMapping("/commands")
    public List<CommandResult> submit(@RequestBody List<Command> commands) {
        return engine.submitBatch(commands);
    }

    @GetMapping("/snapshot")
    public EngineSnapshot snapshot() {
        return engine.snapshot();
    }
}
