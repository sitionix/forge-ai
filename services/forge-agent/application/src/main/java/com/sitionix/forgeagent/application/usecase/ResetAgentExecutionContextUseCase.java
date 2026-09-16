package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.model.AgentContextResetEligibility;
import com.sitionix.forgeagent.domain.model.AgentExecutionContext;
import com.sitionix.forgeagent.domain.model.AgentExecutionSession;
import com.sitionix.forgeagent.domain.model.NodeContextMode;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ResetAgentExecutionContextUseCase {
    private final AgentExecutionSessionRepository sessions;

    @Transactional
    public List<AgentExecutionContext> execute(final UUID sessionId) {
        // Read immutable identity first; match allocator order: scope advisory lock -> session row.
        final AgentExecutionSession identity = this.sessions.findSession(sessionId).orElseThrow(() -> missing(sessionId));
        if (identity.contextMode() == NodeContextMode.SHARED_SESSION_GROUP) {
            this.sessions.lockSharedScope(identity.workflowRunId(), identity.contextIterationId(), identity.contextGroupKey(), identity.repositoryId());
        } else {
            this.sessions.lockReusableScope(identity.workflowRunId(), identity.sourceNodeId(), identity.repositoryId());
        }
        final AgentExecutionSession session = this.sessions.lockSession(sessionId).orElseThrow(() -> missing(sessionId));
        if (!session.contextMode().reusable()) {
            throw new ConflictException(AgentContextResetEligibility.NOT_ALLOWED, "Fresh agent contexts cannot be reset.");
        }
        if (session.contextResetAt() == null) {
            final String reason = AgentContextResetEligibility.reason(session, this.sessions.hasPendingTurns(sessionId));
            if (reason != null) {
                throw new ConflictException(reason, AgentContextResetEligibility.BUSY.equals(reason)
                        ? "The agent context has pending or active work." : "The agent context cannot be reset.");
            }
            this.sessions.markContextReset(sessionId);
        }
        return this.sessions.findContextsByWorkflowRunId(session.workflowRunId()).stream()
                .filter(allocation -> allocation.session().id().equals(sessionId)).toList();
    }

    private static NotFoundException missing(final UUID sessionId) {
        return new NotFoundException("AGENT_EXECUTION_SESSION_NOT_FOUND", "Agent execution session was not found: " + sessionId);
    }
}
