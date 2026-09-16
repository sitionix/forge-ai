package com.sitionix.forgeagent.api;

import com.sitionix.forgeagent.api.dto.AgentExecutionContextResponse;
import com.sitionix.forgeagent.application.usecase.AgentExecutionContextUseCases;
import com.sitionix.forgeagent.application.usecase.ResetAgentExecutionContextUseCase;
import com.sitionix.forgeagent.domain.model.AgentContextResetEligibility;
import com.sitionix.forgeagent.domain.model.AgentExecutionContext;
import com.sitionix.forgeagent.domain.model.AgentExecutionTurn;
import com.sitionix.forgeagent.domain.model.AgentContextForkEligibility;
import com.sitionix.forgeagent.application.runtime.AgentContextForkProvider;
import com.sitionix.forgeagent.application.usecase.ForkAgentExecutionContextUseCase;
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
    private final ForkAgentExecutionContextUseCase forkContext;
    private final AgentContextForkProvider forkProvider;

    @GetMapping("/api/v1/workflow-runs/{runId}/agent-execution-contexts")
    public List<AgentExecutionContextResponse> list(@PathVariable final UUID runId) {
        return this.responses(this.useCases.list(runId));
    }

    @PostMapping("/api/v1/agent-execution-sessions/{sessionId}/reset-context")
    public List<AgentExecutionContextResponse> reset(@PathVariable final UUID sessionId) {
        return this.responses(this.resetContext.execute(sessionId));
    }

    @PostMapping("/api/v1/agent-execution-sessions/{sessionId}/fork-context")
    public List<AgentExecutionContextResponse> fork(@PathVariable final UUID sessionId) {
        return this.responses(this.forkContext.execute(sessionId));
    }

    private List<AgentExecutionContextResponse> responses(final List<AgentExecutionContext> contexts) {
        final var pendingSessionIds = contexts.stream()
                .filter(context -> context.turn() != null && AgentContextResetEligibility.pending(context.turn()))
                .map(context -> context.session().id()).collect(Collectors.toSet());
        final java.util.Map<UUID, AgentExecutionTurn> latest = new java.util.HashMap<>();
        contexts.stream().filter(context -> context.turn() != null).forEach(context ->
                latest.merge(context.session().id(), context.turn(), (a,b) -> a.sequence() > b.sequence() ? a : b));
        return contexts.stream().map(context -> {
            final var session = context.session();
            final boolean pending = pendingSessionIds.contains(session.id());
            String forkReason = AgentContextForkEligibility.reason(session, latest.get(session.id()), pending, context.workflowTerminal());
            if (forkReason == null && !this.forkProvider.supports(session.providerId(), session.providerVersion())) {
                forkReason = AgentContextForkEligibility.UNSUPPORTED;
            }
            return this.mapper.toResponse(context, AgentContextResetEligibility.reason(session, pending), forkReason);
        }).toList();
    }
}
