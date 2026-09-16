package com.sitionix.forgeai.api;

import com.sitionix.forgeai.api.agentproxy.AgentExecutionContextResponse;
import com.sitionix.forgeai.domain.usecase.GetAgentExecutionContexts;
import com.sitionix.forgeai.domain.usecase.ResetAgentExecutionContext;
import com.sitionix.forgeai.domain.usecase.ForkAgentExecutionContext;
import org.springframework.web.bind.annotation.PostMapping;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequiredArgsConstructor
public class ForgeAiAgentExecutionContextsController {
    private final GetAgentExecutionContexts contexts;
    private final ResetAgentExecutionContext resetContext;
    private final ForkAgentExecutionContext forkContext;
    @GetMapping("/api/v1/infrastructure/agents/workflow-runs/{runId}/agent-execution-contexts")
    public List<AgentExecutionContextResponse> list(@PathVariable UUID runId) {
        return contexts.execute(runId).stream().map(this::response).toList();
    }

    @PostMapping("/api/v1/infrastructure/agents/agent-execution-sessions/{sessionId}/reset-context")
    public List<AgentExecutionContextResponse> reset(@PathVariable UUID sessionId) {
        return resetContext.execute(sessionId).stream().map(this::response).toList();
    }

    @PostMapping("/api/v1/infrastructure/agents/agent-execution-sessions/{sessionId}/fork-context")
    public List<AgentExecutionContextResponse> fork(@PathVariable UUID sessionId) {
        return forkContext.execute(sessionId).stream().map(this::response).toList();
    }

    private AgentExecutionContextResponse response(com.sitionix.forgeai.domain.model.agentproxy.AgentExecutionContext value) {
        return new AgentExecutionContextResponse(value.sessionId(), value.turnId(), value.nodeRunId(), value.sourceNodeId(), value.repositoryId(), value.contextMode(), value.sequence(), value.sessionStatus(), value.turnStatus(), value.provider(), value.providerConversationId(), value.providerTurnId(), value.providerVersion(), value.failureCode(), value.failureMessage(), value.createdAt(), value.startedAt(), value.finishedAt(), value.contextResetAt(), value.resetAllowed(), value.resetReason(), value.contextIterationId(), value.contextGroupKey(), value.contextForkedAt(), value.forkedFromSessionId(), value.forkedFromTurnId(), value.forkAllowed(), value.forkReason());
    }
}
