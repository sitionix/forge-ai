package com.sitionix.forgeagent.api;

import com.sitionix.forgeagent.api.dto.AgentExecutionContextResponse;
import com.sitionix.forgeagent.application.usecase.AgentExecutionContextUseCases;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AgentExecutionContextsController {
    private final AgentExecutionContextUseCases useCases;
    private final ForgeAgentApiMapper mapper;

    @GetMapping("/api/v1/workflow-runs/{runId}/agent-execution-contexts")
    public List<AgentExecutionContextResponse> list(@PathVariable final UUID runId) {
        return this.useCases.list(runId).stream().map(this.mapper::toResponse).toList();
    }
}
