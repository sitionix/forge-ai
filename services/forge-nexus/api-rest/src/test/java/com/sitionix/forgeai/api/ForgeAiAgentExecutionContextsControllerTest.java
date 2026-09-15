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

        var response = new ForgeAiAgentExecutionContextsController(list, reset).reset(sessionId);

        assertThat(response).hasSize(1);
        assertThat(response.getFirst()).usingRecursiveComparison().isEqualTo(row);
        verify(reset).execute(sessionId);
        verifyNoInteractions(list);
    }
}
