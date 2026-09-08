package com.sitionix.forgeai.api;

import com.sitionix.forgeai.api.agentproxy.AgentExecutionEventPageResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProxyApiMapper;
import com.sitionix.forgeai.domain.usecase.GetAgentExecutionEvents;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ForgeAiAgentExecutionEventsController {
    private final GetAgentExecutionEvents events;
    private final AgentProxyApiMapper mapper;

    @GetMapping("/api/v1/infrastructure/agents/agent-execution-turns/{turnId}/events")
    public AgentExecutionEventPageResponse page(
            @PathVariable final UUID turnId,
            @RequestParam(defaultValue = "0") final long afterSequence,
            @RequestParam(defaultValue = "100") final int limit) {
        return this.mapper.toResponse(this.events.execute(turnId, afterSequence, limit));
    }
}
