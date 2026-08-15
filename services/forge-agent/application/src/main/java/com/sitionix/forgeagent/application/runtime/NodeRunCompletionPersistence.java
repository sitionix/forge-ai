package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunFailure;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NodeRunCompletionPersistence {

    private final NodeRunRepository nodeRunRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowCompletionPolicy completionPolicy;
    private final WorkflowExecutionCoordinator coordinator;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markBusinessSucceeded(final UUID nodeRunId, final NodeRunOutput output) {
        final CompletionTarget target = this.lockCompletionTarget(nodeRunId);
        if (target == null) {
            return false;
        }
        final NodeRun nodeRun = target.nodeRun();
        if (nodeRun.status() == NodeRunStatus.SUCCEEDED && output.equals(nodeRun.output())) {
            return false;
        }
        if (this.isTerminal(nodeRun.status())) {
            throw this.conflict("Node run already has a different terminal outcome.");
        }
        if (nodeRun.status() != NodeRunStatus.RUNNING) {
            throw this.conflict("Only RUNNING node runs can succeed.");
        }
        this.nodeRunRepository.saveAndFlush(this.withSucceeded(nodeRun, output, Instant.now(this.clock)));
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompletionFailed(final UUID nodeRunId, final NodeRunFailure failure) {
        final Optional<UUID> workflowRunId = this.nodeRunRepository.findWorkflowRunIdById(nodeRunId);
        if (workflowRunId.isEmpty()) {
            return;
        }
        final WorkflowRun workflowRun = this.workflowRunRepository.findByIdForUpdate(workflowRunId.get())
                .orElseThrow(() -> new ConflictException("WORKFLOW_RUN_NOT_FOUND", "Owning workflow run was not found."));
        final NodeRun current = this.nodeRunRepository.findByIdForUpdate(nodeRunId)
                .orElseThrow(() -> new ConflictException("NODE_RUN_NOT_FOUND", "Node run was not found."));
        if (current.routingCompletedAt() != null || current.status() != NodeRunStatus.SUCCEEDED) {
            return;
        }
        this.nodeRunRepository.saveAndFlush(this.withFailed(current, failure, Instant.now(this.clock)));
        this.reconcileWorkflowRun(workflowRun);
    }

    private CompletionTarget lockCompletionTarget(final UUID nodeRunId) {
        final Optional<UUID> workflowRunId = this.nodeRunRepository.findWorkflowRunIdById(nodeRunId);
        if (workflowRunId.isEmpty()) {
            return null;
        }
        final WorkflowRun workflowRun = this.workflowRunRepository.findByIdForUpdate(workflowRunId.get())
                .orElseThrow(() -> new ConflictException("WORKFLOW_RUN_NOT_FOUND", "Owning workflow run was not found."));
        final Optional<NodeRun> locked = this.nodeRunRepository.findByIdForUpdate(nodeRunId);
        return locked.map(nodeRun -> new CompletionTarget(workflowRun, nodeRun)).orElse(null);
    }

    private void reconcileWorkflowRun(final WorkflowRun lockedWorkflowRun) {
        if (lockedWorkflowRun.finishedAt() != null || this.isTerminal(lockedWorkflowRun.status())) {
            return;
        }
        this.completionPolicy.evaluate(lockedWorkflowRun).apply(this.coordinator.completionDecisionHandler(lockedWorkflowRun));
    }

    private boolean isTerminal(final NodeRunStatus status) {
        return status == NodeRunStatus.SUCCEEDED
                || status == NodeRunStatus.FAILED
                || status == NodeRunStatus.BLOCKED
                || status == NodeRunStatus.CANCELLED;
    }

    private boolean isTerminal(final WorkflowRunStatus status) {
        return status == WorkflowRunStatus.SUCCEEDED
                || status == WorkflowRunStatus.FAILED
                || status == WorkflowRunStatus.CANCELLED;
    }

    private NodeRun withSucceeded(final NodeRun nodeRun, final NodeRunOutput output, final Instant now) {
        return new NodeRun(
                nodeRun.id(),
                nodeRun.workflowRunId(),
                nodeRun.sourceNodeId(),
                nodeRun.sourceAgentId(),
                nodeRun.agentName(),
                nodeRun.agentInstructions(),
                nodeRun.agentOutputSchema(),
                nodeRun.inputMode(),
                nodeRun.position(),
                nodeRun.executionFrameId(),
                nodeRun.enteredViaInputPortId(),
                nodeRun.activationFrameId(),
                nodeRun.selectedOutputPortId(),
                NodeRunStatus.SUCCEEDED,
                output,
                nodeRun.failure(),
                nodeRun.executionModel(),
                nodeRun.createdAt(),
                nodeRun.startedAt(),
                nodeRun.finishedAt() == null ? now : nodeRun.finishedAt()
        );
    }

    private NodeRun withFailed(final NodeRun nodeRun, final NodeRunFailure failure, final Instant now) {
        return new NodeRun(
                nodeRun.id(),
                nodeRun.workflowRunId(),
                nodeRun.sourceNodeId(),
                nodeRun.sourceAgentId(),
                nodeRun.agentName(),
                nodeRun.agentInstructions(),
                nodeRun.agentOutputSchema(),
                nodeRun.inputMode(),
                nodeRun.position(),
                nodeRun.executionFrameId(),
                nodeRun.enteredViaInputPortId(),
                nodeRun.activationFrameId(),
                nodeRun.selectedOutputPortId(),
                NodeRunStatus.FAILED,
                nodeRun.output(),
                failure,
                nodeRun.executionModel(),
                nodeRun.createdAt(),
                nodeRun.startedAt(),
                nodeRun.finishedAt() == null ? now : nodeRun.finishedAt()
        );
    }

    private ConflictException conflict(final String message) {
        return new ConflictException(NodeRunLifecycle.LIFECYCLE_CONFLICT, message);
    }

    private record CompletionTarget(WorkflowRun workflowRun, NodeRun nodeRun) {
    }
}
