package com.sitionix.forgeagent.api;

import com.sitionix.forgeagent.api.dto.AgentExecutionContextResponse;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AgentExecutionContextsController {
    private final AgentExecutionSessionRepository sessions;

    @GetMapping("/api/v1/workflow-runs/{runId}/agent-execution-contexts")
    public List<AgentExecutionContextResponse> list(@PathVariable final UUID runId) {
        return this.sessions.findByWorkflowRunId(runId).stream().map(allocation -> {
            final var session=allocation.session(); final var turn=allocation.turn();
            return new AgentExecutionContextResponse(session.id(),turn.id(),turn.nodeRunId(),session.sourceNodeId(),
                    session.repositoryId(),session.contextMode().name(),turn.sequence(),session.status().name(),turn.status().name(),
                    session.providerId(),session.providerConversationId(),turn.providerTurnId(),session.providerVersion(),
                    turn.failureCode(),turn.failureMessage(),session.createdAt(),turn.startedAt(),turn.finishedAt());
        }).toList();
    }
}
