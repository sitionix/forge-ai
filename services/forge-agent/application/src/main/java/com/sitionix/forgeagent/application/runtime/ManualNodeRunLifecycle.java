package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.NodeType;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManualNodeRunLifecycle {
    private final NodeRunRepository nodeRuns;
    private final WorkflowRunRepository workflowRuns;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void waitForSelection(final UUID nodeRunId) {
        final var workflowRunId = this.nodeRuns.findWorkflowRunIdById(nodeRunId);
        if (workflowRunId.isEmpty()) return;
        // Use the same lock order as cancellation and completion.
        final var owningRun = this.workflowRuns.findByIdForUpdate(workflowRunId.get());
        if (owningRun.isEmpty()) return;
        final WorkflowRun run = owningRun.get();
        if (run.status() != WorkflowRunStatus.QUEUED && run.status() != WorkflowRunStatus.RUNNING) return;
        final var locked = this.nodeRuns.findByIdForUpdate(nodeRunId);
        if (locked.isEmpty()) return;
        final NodeRun node = locked.get();
        if (node.nodeType() != NodeType.MANUAL || node.status() != NodeRunStatus.PENDING
                || node.executionFrameId() == null) return;

        final Instant now = Instant.now(this.clock);
        this.nodeRuns.save(new NodeRun(
                node.id(), node.workflowRunId(), node.sourceNodeId(), node.sourceAgentId(), node.agentName(),
                node.agentInstructions(), node.agentOutputSchema(), node.inputMode(), node.position(),
                node.executionFrameId(), node.enteredViaInputPortId(), node.activationFrameId(),
                node.selectedOutputPortId(), node.routingCompletedAt(), NodeRunStatus.WAITING_FOR_MANUAL,
                node.output(), node.failure(), node.executionModel(), node.createdAt(),
                node.startedAt() == null ? now : node.startedAt(), node.finishedAt(), node.repositoryId(),
                node.contextMode(), node.contextTrackingVersion(), node.retryOfNodeRunId(),
                node.contextGroupKey(), node.contextIterationId(), node.nodeType()));
        if (run.status() == WorkflowRunStatus.QUEUED) {
            this.workflowRuns.saveLifecycle(new WorkflowRun(
                    run.id(), run.projectId(), run.sourceWorkflowId(), run.taskId(), run.workflowName(), run.input(),
                    WorkflowRunStatus.RUNNING, run.nodeRuns(), run.connectionResolutions(), run.executionEdges(),
                    run.runtimeGraph(), run.result(), run.resultSourceNodeRunId(), run.createdAt(),
                    run.startedAt() == null ? now : run.startedAt(), run.finishedAt(), run.repositoryIds(),
                    run.operatorStopStatus(), run.operatorStopFailureCode(), run.operatorStopPendingNodeRunIds(),
                    run.operatorStopAttempt()));
        }
    }
}
