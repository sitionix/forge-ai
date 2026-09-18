package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRun;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import java.util.UUID;
import java.util.List;
import java.time.Instant;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SelectAgentManualNodeOutputUseCaseTest {
    @Mock private ForgeAgentClient forgeAgentClient;
    @InjectMocks private SelectAgentManualNodeOutputUseCase useCase;

    @Test
    void delegatesExactSelectionAndReturnsClientWorkflowRun() {
        final UUID runId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        final UUID nodeRunId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        final UUID outputPortId = UUID.fromString("33333333-3333-4333-8333-333333333333");
        final AgentWorkflowRun expected = new AgentWorkflowRun(runId, UUID.randomUUID(), UUID.randomUUID(),
                null, "Manual selection", "Input", AgentWorkflowRunStatus.RUNNING, List.of(), List.of(), List.of(),
                null, null, null, Instant.EPOCH, Instant.EPOCH, null, List.of());
        when(this.forgeAgentClient.selectManualNodeOutput(runId, nodeRunId, outputPortId)).thenReturn(expected);

        assertThat(this.useCase.execute(runId, nodeRunId, outputPortId)).isSameAs(expected);

        verify(this.forgeAgentClient).selectManualNodeOutput(runId, nodeRunId, outputPortId);
        verifyNoMoreInteractions(this.forgeAgentClient);
    }
}
