package com.sitionix.forgeagent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.PortDirection;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
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
    private final WorkflowRunGraphRepository graphs;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void selectOutput(final UUID workflowRunId, final UUID nodeRunId, final UUID outputPortId) {
        final WorkflowRun run = this.workflowRuns.findByIdForUpdate(workflowRunId)
                .orElseThrow(() -> new NotFoundException("WORKFLOW_RUN_NOT_FOUND", "Workflow run was not found."));
        final NodeRun node = this.nodeRuns.findByIdForUpdate(nodeRunId)
                .orElseThrow(() -> new NotFoundException("NODE_RUN_NOT_FOUND", "Node run was not found."));
        if (!workflowRunId.equals(node.workflowRunId())) {
            throw new NotFoundException("NODE_RUN_NOT_FOUND", "Node run was not found in the workflow run.");
        }
        if (node.nodeType() != NodeType.MANUAL) {
            throw new ConflictException("MANUAL_SELECTION_NOT_ALLOWED", "Only manual nodes accept an output selection.");
        }
        if (outputPortId == null) {
            throw new ValidationException("INVALID_MANUAL_OUTPUT_PORT", "An output port is required.");
        }
        // A retry must also succeed after routing has completed the workflow.
        if (node.status() == NodeRunStatus.SUCCEEDED) {
            if (outputPortId.equals(node.selectedOutputPortId())) return;
            throw new ConflictException("MANUAL_SELECTION_CONFLICT", "A different output was already selected.");
        }
        if (node.status() != NodeRunStatus.WAITING_FOR_MANUAL) {
            throw new ConflictException("MANUAL_SELECTION_NOT_WAITING", "The node is not waiting for a manual selection.");
        }
        if (run.status() != WorkflowRunStatus.QUEUED && run.status() != WorkflowRunStatus.RUNNING) {
            throw new ConflictException("WORKFLOW_RUN_NOT_ACTIVE", "The workflow run is no longer active.");
        }
        final var port = this.graphs.findPort(workflowRunId, outputPortId)
                .filter(p -> p.sourceNodeId().equals(node.sourceNodeId()) && p.direction() == PortDirection.OUTPUT)
                .orElseThrow(() -> new ValidationException("INVALID_MANUAL_OUTPUT_PORT",
                        "The output port does not belong to this snapshotted node."));
        final NodeRunOutput output = new NodeRunOutput(this.json.createObjectNode()
                .put("selectedOutputPortId", port.sourcePortId().toString())
                .put("selectedOutputName", port.name()).toString());
        this.nodeRuns.saveAndFlush(new NodeRun(
                node.id(), node.workflowRunId(), node.sourceNodeId(), node.sourceAgentId(), node.agentName(),
                node.agentInstructions(), node.agentOutputSchema(), node.inputMode(), node.position(),
                node.executionFrameId(), node.enteredViaInputPortId(), node.activationFrameId(),
                outputPortId, node.routingCompletedAt(), NodeRunStatus.SUCCEEDED,
                output, node.failure(), node.executionModel(), node.createdAt(), node.startedAt(),
                Instant.now(this.clock), node.repositoryId(), node.contextMode(), node.contextTrackingVersion(),
                node.retryOfNodeRunId(), node.contextGroupKey(), node.contextIterationId(), node.nodeType()));
    }

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
