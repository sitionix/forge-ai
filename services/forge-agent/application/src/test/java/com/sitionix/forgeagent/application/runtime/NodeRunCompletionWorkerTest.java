package com.sitionix.forgeagent.application.runtime;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NodeRunCompletionWorkerTest {

    private final NodeRunRepository nodeRuns = mock(NodeRunRepository.class);
    private final NodeRunCompletionProcessor processor = mock(NodeRunCompletionProcessor.class);
    private final WorkflowExecutionCoordinator coordinator = mock(WorkflowExecutionCoordinator.class);
    private final NodeRunCompletionWorker worker = new NodeRunCompletionWorker(
            this.nodeRuns, this.processor, this.coordinator);

    @Test
    void retriesStrandedWorkflowAndDoesNotBlockOtherCompletionWorkAfterTransientFailure() {
        final UUID failedWorkflow = UUID.randomUUID();
        final UUID otherWorkflow = UUID.randomUUID();
        when(this.nodeRuns.findSuccessfulUnroutedIds()).thenReturn(List.of());
        when(this.nodeRuns.findWorkflowRunIdsRequiringCompletion())
                .thenReturn(List.of(failedWorkflow, otherWorkflow));
        doThrow(new IllegalStateException("transient failure"))
                .doNothing()
                .when(this.coordinator).reconcile(failedWorkflow);

        this.worker.poll();
        this.worker.poll();

        verify(this.coordinator, times(2)).reconcile(failedWorkflow);
        verify(this.coordinator, times(2)).reconcile(otherWorkflow);
    }
}
