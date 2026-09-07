package com.sitionix.forgeagent.api;

import com.sitionix.forgeagent.api.dto.AgentExecutionEventPageResponse;
import com.sitionix.forgeagent.application.usecase.AgentExecutionEventUseCases;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AgentExecutionEventsController {
    private final AgentExecutionEventUseCases useCases;
    private final ForgeAgentApiMapper mapper;

    @GetMapping("/api/v1/agent-execution-turns/{turnId}/events")
    public AgentExecutionEventPageResponse page(
            @PathVariable final UUID turnId,
            @RequestParam(defaultValue = "0") final long afterSequence,
            @RequestParam(defaultValue = "100") final int limit) {
        return this.mapper.toResponse(this.useCases.page(turnId, afterSequence, limit));
    }
}
