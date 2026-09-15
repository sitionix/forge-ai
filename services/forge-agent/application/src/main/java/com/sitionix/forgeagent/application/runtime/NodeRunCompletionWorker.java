package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class NodeRunCompletionWorker {

    private final NodeRunRepository nodeRunRepository;
    private final NodeRunCompletionProcessor processor;
    private final WorkflowExecutionCoordinator coordinator;

    public void poll() {
        for (final UUID nodeRunId : this.nodeRunRepository.findSuccessfulUnroutedIds()) {
            this.processor.process(nodeRunId);
        }
        for (final UUID workflowRunId : this.nodeRunRepository.findWorkflowRunIdsRequiringCompletion()) {
            try {
                this.coordinator.reconcile(workflowRunId);
            } catch (final RuntimeException exception) {
                log.warn("Workflow completion reconciliation will be retried. workflowRunId={}",
                        workflowRunId, exception);
            }
        }
    }
}
