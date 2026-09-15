package com.sitionix.forgeagent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.usecase.AgentExecutionContextUseCases;
import com.sitionix.forgeagent.application.usecase.ResetAgentExecutionContextUseCase;
import com.sitionix.forgeagent.domain.model.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentExecutionContextsControllerTest {
    private final AgentExecutionContextUseCases list = mock(AgentExecutionContextUseCases.class);
    private final ResetAgentExecutionContextUseCase reset = mock(ResetAgentExecutionContextUseCase.class);
    private final AgentExecutionContextsController controller = new AgentExecutionContextsController(
            this.list, new ForgeAgentApiMapper(new ObjectMapper()), this.reset);

    @Test
    void listUsesSessionWidePendingTruthAndFreshAndRetiredEligibility() {
        final var idle = session(NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE, null);
        final var queued = session(NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE, null);
        final var fresh = session(NodeContextMode.FRESH_EACH_NODE_RUN, null);
        final var retired = session(NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE, Instant.now());
        when(this.list.list(idle.workflowRunId())).thenReturn(List.of(
                allocation(idle, AgentExecutionTurnStatus.SUCCEEDED, 1),
                allocation(queued, AgentExecutionTurnStatus.SUCCEEDED, 1),
                allocation(queued, AgentExecutionTurnStatus.QUEUED, 2),
                allocation(fresh, AgentExecutionTurnStatus.SUCCEEDED, 1),
                allocation(retired, AgentExecutionTurnStatus.SUCCEEDED, 1)));

        final var rows = this.controller.list(idle.workflowRunId());

        assertThat(rows).hasSize(5);
        assertThat(rows.get(0).resetAllowed()).isTrue();
        assertThat(rows.get(0).resetReason()).isNull();
        assertThat(rows.get(0).contextResetAt()).isNull();
        assertThat(rows.subList(1, 3)).allSatisfy(row -> {
            assertThat(row.resetAllowed()).isFalse();
            assertThat(row.resetReason()).isEqualTo(AgentContextResetEligibility.BUSY);
        });
        assertThat(rows.subList(3, 5)).allSatisfy(row -> {
            assertThat(row.resetAllowed()).isFalse();
            assertThat(row.resetReason()).isEqualTo(AgentContextResetEligibility.NOT_ALLOWED);
        });
        assertThat(rows.get(4).contextResetAt()).isEqualTo(retired.contextResetAt());
    }

    @Test
    void resetReturnsAuthoritativeRetirementAndEveryHistoricalTurn() {
        final var session = session(NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE, Instant.now());
        final var first = allocation(session, AgentExecutionTurnStatus.SUCCEEDED, 1);
        final var second = allocation(session, AgentExecutionTurnStatus.FAILED, 2);
        when(this.reset.execute(session.id())).thenReturn(List.of(first, second));

        final var rows = this.controller.reset(session.id());

        assertThat(rows).extracting(row -> row.turnId()).containsExactly(first.turn().id(), second.turn().id());
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.sessionId()).isEqualTo(session.id());
            assertThat(row.contextResetAt()).isEqualTo(session.contextResetAt());
            assertThat(row.resetAllowed()).isFalse();
            assertThat(row.providerConversationId()).isEqualTo(session.providerConversationId());
        });
        verify(this.reset).execute(session.id());
    }

    private static AgentExecutionSession session(final NodeContextMode mode, final Instant resetAt) {
        return new AgentExecutionSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                "codex", "thread-original", "0.154.0", mode, AgentExecutionSessionStatus.IDLE, null, null, null, 1,
                null, null, null, Instant.now(), Instant.now(), null, resetAt);
    }

    private static AgentExecutionAllocation allocation(final AgentExecutionSession session,
                                                       final AgentExecutionTurnStatus status, final int sequence) {
        return new AgentExecutionAllocation(session, new AgentExecutionTurn(UUID.randomUUID(), session.id(), UUID.randomUUID(),
                "turn-" + sequence, sequence, status, null, null, null, null, null,
                Instant.now(), Instant.now(), Instant.now(), Instant.now()));
    }
}
