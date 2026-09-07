package com.sitionix.forgeai.api;

import com.sitionix.forgeai.api.agentproxy.AgentExecutionContextResponse;
import com.sitionix.forgeai.domain.usecase.GetAgentExecutionContexts;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequiredArgsConstructor
public class ForgeAiAgentExecutionContextsController {
    private final GetAgentExecutionContexts contexts;
    @GetMapping("/api/v1/infrastructure/agents/workflow-runs/{runId}/agent-execution-contexts")
    public List<AgentExecutionContextResponse> list(@PathVariable UUID runId) {
        return contexts.execute(runId).stream().map(value -> new AgentExecutionContextResponse(value.sessionId(), value.turnId(), value.nodeRunId(), value.sourceNodeId(), value.repositoryId(), value.contextMode(), value.sequence(), value.sessionStatus(), value.turnStatus(), value.provider(), value.providerConversationId(), value.providerTurnId(), value.providerVersion(), value.failureCode(), value.failureMessage(), value.createdAt(), value.startedAt(), value.finishedAt())).toList();
    }
}
