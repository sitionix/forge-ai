package com.sitionix.forgeagent.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResetAgentExecutionContextUseCaseTest {
    private final AgentExecutionSessionRepository sessions = mock(AgentExecutionSessionRepository.class);
    private final ResetAgentExecutionContextUseCase useCase = new ResetAgentExecutionContextUseCase(this.sessions);

    @Test
    void scopeLockPrecedesRowLockAndRetirement() {
        final var session = session(NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE, AgentExecutionSessionStatus.IDLE, null);
        prepare(session);
        assertThat(this.useCase.execute(session.id())).isEmpty();
        final var order = inOrder(this.sessions);
        order.verify(this.sessions).findSession(session.id());
        order.verify(this.sessions).lockReusableScope(session.workflowRunId(), session.sourceNodeId(), session.repositoryId());
        order.verify(this.sessions).lockSession(session.id());
        order.verify(this.sessions).hasPendingTurns(session.id());
        order.verify(this.sessions).markContextReset(session.id());
        order.verify(this.sessions).findByWorkflowRunId(session.workflowRunId());
        order.verifyNoMoreInteractions();
    }

    @Test
    void queuedTurnFailsClosed() {
        final var session = session(NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE, AgentExecutionSessionStatus.IDLE, null);
        prepare(session);
        when(this.sessions.hasPendingTurns(session.id())).thenReturn(true);
        assertThatThrownBy(() -> this.useCase.execute(session.id())).isInstanceOf(ConflictException.class)
                .hasMessageContaining("pending or active");
        verify(this.sessions, never()).markContextReset(any());
    }

    @Test
    void alreadyRetiredIsIdempotent() {
        final var session = session(NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE, AgentExecutionSessionStatus.IDLE, Instant.now());
        prepare(session);
        this.useCase.execute(session.id());
        this.useCase.execute(session.id());
        verify(this.sessions, never()).markContextReset(any());
        verify(this.sessions, never()).hasPendingTurns(any());
        verify(this.sessions, never()).allocate(any(), any());
    }

    @Test
    void freshAndUnsafeSessionsAreRejected() {
        for (final var mode : NodeContextMode.values()) {
            for (final var status : AgentExecutionSessionStatus.values()) {
                if (mode.reusable() && status == AgentExecutionSessionStatus.IDLE) continue;
                final var session = session(mode, status, null);
                prepare(session);
                assertThatThrownBy(() -> this.useCase.execute(session.id())).isInstanceOf(ConflictException.class);
                verify(this.sessions, never()).markContextReset(session.id());
            }
        }
    }

    @Test
    void readEligibilityUsesAllPendingStatusesAndRetirementTruth() {
        final var idle = session(NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE, AgentExecutionSessionStatus.IDLE, null);
        assertThat(AgentContextResetEligibility.reason(idle, false)).isNull();
        assertThat(AgentContextResetEligibility.reason(idle, true)).isEqualTo(AgentContextResetEligibility.BUSY);
        assertThat(AgentContextResetEligibility.reason(session(NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE,
                AgentExecutionSessionStatus.IDLE, Instant.now()), false)).isEqualTo(AgentContextResetEligibility.NOT_ALLOWED);
    }

    private void prepare(final AgentExecutionSession session) {
        when(this.sessions.findSession(session.id())).thenReturn(Optional.of(session));
        when(this.sessions.lockSession(session.id())).thenReturn(Optional.of(session));
        when(this.sessions.findByWorkflowRunId(session.workflowRunId())).thenReturn(List.of());
    }

    private static AgentExecutionSession session(final NodeContextMode mode, final AgentExecutionSessionStatus status,
                                                  final Instant resetAt) {
        return new AgentExecutionSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                "codex", "thread-1", "0.157.0", mode, status, null, null, null, 1, null, null, null,
                Instant.now(), Instant.now(), null, resetAt, mode == NodeContextMode.REUSE_WITHIN_WORKFLOW_ITERATION ? UUID.randomUUID() : null);
    }
}
