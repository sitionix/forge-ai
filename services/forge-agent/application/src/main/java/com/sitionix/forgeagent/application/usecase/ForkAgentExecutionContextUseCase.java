package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.application.runtime.AgentContextForkProvider;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.model.AgentContextForkEligibility;
import com.sitionix.forgeagent.domain.model.AgentExecutionContext;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ForkAgentExecutionContextUseCase {
    private final AgentExecutionSessionRepository sessions;
    private final AgentContextForkProvider provider;
    private final AgentExecutionContextUseCases contexts;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<AgentExecutionContext> execute(final UUID sessionId) {
        final var identity = this.sessions.findSession(sessionId).orElseThrow(() -> new NotFoundException(
                "AGENT_EXECUTION_SESSION_NOT_FOUND", "Agent execution session was not found: " + sessionId));
        // Installed protocol validation is provider I/O and precedes any state mutation.
        this.provider.validateSupport(identity.providerId(), identity.providerVersion());
        final var prepared = this.sessions.prepareFork(sessionId);
        final String childThread;
        try {
            childThread = this.provider.fork(prepared.session().providerId(), prepared.session().providerVersion(),
                    prepared.session().providerConversationId(), prepared.turn().providerTurnId());
            if (childThread == null || childThread.isBlank() || childThread.equals(prepared.session().providerConversationId())) {
                throw new IllegalStateException("Invalid fork identity");
            }
        } catch (final RuntimeException failure) {
            this.sessions.abortFork(prepared);
            throw new ConflictException(AgentContextForkEligibility.FAILED, "The provider context fork failed; no successor was installed.");
        }
        try {
            this.sessions.completeFork(prepared, childThread);
        } catch (final RuntimeException conflict) {
            throw new ConflictException(AgentContextForkEligibility.CONFLICT, "The provider fork could not be installed as the current context.");
        }
        return this.contexts.list(identity.workflowRunId());
    }
}
