package com.sitionix.forgeagent.api;

import com.sitionix.forgeagent.api.dto.AgentExecutionContextResponse;
import com.sitionix.forgeagent.application.usecase.AgentExecutionContextUseCases;
import com.sitionix.forgeagent.application.usecase.ResetAgentExecutionContextUseCase;
import com.sitionix.forgeagent.domain.model.AgentContextResetEligibility;
import com.sitionix.forgeagent.domain.model.AgentExecutionAllocation;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final ResetAgentExecutionContextUseCase resetContext;

    @GetMapping("/api/v1/workflow-runs/{runId}/agent-execution-contexts")
    public List<AgentExecutionContextResponse> list(@PathVariable final UUID runId) {
        return this.responses(this.useCases.list(runId));
    }

    @PostMapping("/api/v1/agent-execution-sessions/{sessionId}/reset-context")
    public List<AgentExecutionContextResponse> reset(@PathVariable final UUID sessionId) {
        return this.responses(this.resetContext.execute(sessionId));
    }

    private List<AgentExecutionContextResponse> responses(
            final List<AgentExecutionAllocation> allocations) {
        final var pendingSessionIds = allocations.stream()
                .filter(allocation -> AgentContextResetEligibility.pending(allocation.turn()))
                .map(allocation -> allocation.session().id()).collect(Collectors.toSet());
        return allocations.stream().map(allocation -> this.mapper.toResponse(allocation,
                AgentContextResetEligibility.reason(allocation.session(),
                        pendingSessionIds.contains(allocation.session().id())))).toList();
    }
}
