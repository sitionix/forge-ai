package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.application.runtime.ManualNodeRunLifecycle;
import com.sitionix.forgeagent.application.runtime.NodeRunCompletionProcessor;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import java.util.UUID;
import java.util.List;
import java.time.Instant;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SelectManualNodeOutputUseCaseTest {
    private static final UUID RUN_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID NODE_RUN_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID OUTPUT_PORT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    @Mock private ManualNodeRunLifecycle lifecycle;
    @Mock private NodeRunCompletionProcessor completion;
    @Mock private WorkflowRunUseCases runs;
    @InjectMocks private SelectManualNodeOutputUseCase useCase;

    @Test
    void selectsOutputProcessesCompletionAndReturnsReloadedRun() {
        final WorkflowRun expected = new WorkflowRun(RUN_ID, UUID.randomUUID(), UUID.randomUUID(),
                null, "Manual selection", "Input", WorkflowRunStatus.RUNNING, List.of(), List.of(), List.of(),
                null, null, null, Instant.EPOCH, Instant.EPOCH, null, List.of());
        when(this.runs.getWorkflowRun(RUN_ID)).thenReturn(expected);

        assertThat(this.useCase.execute(RUN_ID, NODE_RUN_ID, OUTPUT_PORT_ID)).isSameAs(expected);

        verify(this.lifecycle).selectOutput(RUN_ID, NODE_RUN_ID, OUTPUT_PORT_ID);
        verify(this.completion).process(NODE_RUN_ID);
        verify(this.runs).getWorkflowRun(RUN_ID);
        verifyNoMoreInteractions(this.lifecycle, this.completion, this.runs);
    }

    @Test
    void persistsSelectionBeforeCompletionAndReloadsOnlyAfterProcessing() {
        this.useCase.execute(RUN_ID, NODE_RUN_ID, OUTPUT_PORT_ID);

        final InOrder order = inOrder(this.lifecycle, this.completion, this.runs);
        order.verify(this.lifecycle).selectOutput(RUN_ID, NODE_RUN_ID, OUTPUT_PORT_ID);
        order.verify(this.completion).process(NODE_RUN_ID);
        order.verify(this.runs).getWorkflowRun(RUN_ID);
        order.verifyNoMoreInteractions();
    }
}
