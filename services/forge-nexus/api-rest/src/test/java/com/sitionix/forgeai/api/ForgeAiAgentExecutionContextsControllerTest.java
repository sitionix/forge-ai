package com.sitionix.forgeai.api;

import com.sitionix.forgeai.domain.model.agentproxy.AgentExecutionContext;
import com.sitionix.forgeai.domain.usecase.GetAgentExecutionContexts;
import com.sitionix.forgeai.domain.usecase.ResetAgentExecutionContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ForgeAiAgentExecutionContextsControllerTest {
    @Test
    void forkPreservesZeroTurnChildAndLineageWithoutInventingInvocationFields() {
        var fork = mock(com.sitionix.forgeai.domain.usecase.ForkAgentExecutionContext.class);
        var parent = UUID.randomUUID();
        var turn = UUID.randomUUID();
        var child = new AgentExecutionContext(UUID.randomUUID(), null, null, null, null,
                "SHARED_SESSION_GROUP", null, "IDLE", null, "CODEX", "child-thread", null, "0.154.0",
                null, null, Instant.now(), null, null, null, true, null, UUID.randomUUID(), "group",
                null, parent, turn, false, "AGENT_CONTEXT_FORK_NOT_ALLOWED");
        when(fork.execute(parent)).thenReturn(List.of(child));
        var response = new ForgeAiAgentExecutionContextsController(mock(GetAgentExecutionContexts.class),
                mock(ResetAgentExecutionContext.class), fork).fork(parent);
        assertThat(response).hasSize(1);
        assertThat(response.getFirst()).usingRecursiveComparison().isEqualTo(child);
        assertThat(response.getFirst().sequence()).isNull();
        assertThat(response.getFirst().nodeRunId()).isNull();
    }

    @Test
    void exposesTypedForkPostEndpoint() throws Exception {
        var method = ForgeAiAgentExecutionContextsController.class.getMethod("fork", UUID.class);
        assertThat(method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class).value())
                .containsExactly("/api/v1/infrastructure/agents/agent-execution-sessions/{sessionId}/fork-context");
    }

    @Test
    void resetReturnsEveryHistoricalTurnWithAuthoritativeResetTruth() {
        var list = mock(GetAgentExecutionContexts.class);
        var reset = mock(ResetAgentExecutionContext.class);
        var sessionId = UUID.randomUUID();
        var resetAt = Instant.parse("2026-09-15T10:00:00Z");
        var row = new AgentExecutionContext(sessionId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, "REUSE_WITHIN_WORKFLOW_NODE", 1, "IDLE", "SUCCEEDED", "CODEX", "conversation-a", "turn-a",
                "version", null, null, resetAt.minusSeconds(10), resetAt.minusSeconds(9), resetAt.minusSeconds(8),
                resetAt, false, "AGENT_CONTEXT_RESET_NOT_ALLOWED");
        when(reset.execute(sessionId)).thenReturn(List.of(row));

        var response = new ForgeAiAgentExecutionContextsController(list, reset, mock(com.sitionix.forgeai.domain.usecase.ForkAgentExecutionContext.class)).reset(sessionId);

        assertThat(response).hasSize(1);
        assertThat(response.getFirst()).usingRecursiveComparison().isEqualTo(row);
        verify(reset).execute(sessionId);
        verifyNoInteractions(list);
    }
    @Test
    void sharedReadAndResetPreserveGroupOwnershipAndTurnIdentity() {
        var list = mock(GetAgentExecutionContexts.class);
        var reset = mock(ResetAgentExecutionContext.class);
        var controller = new ForgeAiAgentExecutionContextsController(list, reset, mock(com.sitionix.forgeai.domain.usecase.ForkAgentExecutionContext.class));
        var sessionId = UUID.randomUUID();
        var iterationId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var now = Instant.parse("2026-09-15T10:00:00Z");
        for (Instant resetAt : new Instant[] { null, now }) {
            var first = new AgentExecutionContext(sessionId, UUID.randomUUID(), UUID.randomUUID(), null,
                    null, "SHARED_SESSION_GROUP", 1, "IDLE", "SUCCEEDED", "CODEX", "shared-thread", "turn-a",
                    "version", null, null, now, now, now, resetAt, resetAt == null, null, iterationId, "implementation-loop");
            var second = new AgentExecutionContext(sessionId, UUID.randomUUID(), UUID.randomUUID(), null,
                    null, "SHARED_SESSION_GROUP", 2, "IDLE", "SUCCEEDED", "CODEX", "shared-thread", "turn-b",
                    "version", null, null, now, now, now, resetAt, resetAt == null, null, iterationId, "implementation-loop");
            when(list.execute(runId)).thenReturn(List.of(first, second));
            when(reset.execute(sessionId)).thenReturn(List.of(first, second));
            var response = resetAt == null ? controller.list(runId) : controller.reset(sessionId);
            assertThat(response).hasSize(2);
            assertThat(response.getFirst()).usingRecursiveComparison().isEqualTo(first);
            assertThat(response.getLast()).usingRecursiveComparison().isEqualTo(second);
            assertThat(response).allSatisfy(row -> {
                assertThat(row.sourceNodeId()).isNull();
                assertThat(row.contextGroupKey()).isEqualTo("implementation-loop");
                assertThat(row.contextIterationId()).isEqualTo(iterationId);
            });
        }
    }
}
