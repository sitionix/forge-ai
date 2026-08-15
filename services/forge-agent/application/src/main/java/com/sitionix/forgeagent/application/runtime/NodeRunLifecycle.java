package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.NodeRunFailure;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.ConnectionResolutionRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NodeRunLifecycle {

    public static final String AGENT_MODEL_NOT_CONFIGURED = "AGENT_MODEL_NOT_CONFIGURED";
    public static final String LIFECYCLE_CONFLICT = "NODE_RUN_LIFECYCLE_CONFLICT";

    private final NodeRunRepository nodeRunRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final ConnectionResolutionRepository resolutionRepository;
    private final NodeInputContentPolicyRegistry inputContentPolicyRegistry;
    private final WorkflowExecutionCoordinator coordinator;
    private final Clock clock;

    @Transactional
    public Optional<NodeExecutionClaim> tryStart(final UUID nodeRunId) {
        final Optional<UUID> workflowRunId = this.nodeRunRepository.findWorkflowRunIdById(nodeRunId);
        if (workflowRunId.isEmpty()) {
            return Optional.empty();
        }

        final WorkflowRun workflowRun = this.workflowRunRepository.findByIdForUpdate(workflowRunId.get())
                .orElseThrow(() -> new ConflictException("WORKFLOW_RUN_NOT_FOUND", "Owning workflow run was not found."));
        if (this.isTerminal(workflowRun.status())) {
            return Optional.empty();
        }
        final Optional<NodeRun> locked = this.nodeRunRepository.findByIdForUpdate(nodeRunId);
        if (locked.isEmpty() || locked.get().status() != NodeRunStatus.PENDING) {
            return Optional.empty();
        }

        final NodeRun nodeRun = locked.get();
        final NodeRunExecutionModel executionModel = nodeRun.executionModel();
        if (executionModel == null || this.isBlank(executionModel.providerId()) || this.isBlank(executionModel.modelId())) {
            this.nodeRunRepository.save(this.withFailed(
                    nodeRun,
                    new NodeRunFailure(AGENT_MODEL_NOT_CONFIGURED, "Snapshotted source agent model is not configured."),
                    Instant.now(this.clock)
            ));
            this.reconcileWorkflowRun(workflowRun, Instant.now(this.clock));
            return Optional.empty();
        }

        final Instant now = Instant.now(this.clock);
        final NodeRun running = this.nodeRunRepository.save(this.withRunning(nodeRun, now));
        if (workflowRun.status() == WorkflowRunStatus.QUEUED) {
            this.workflowRunRepository.saveLifecycle(new WorkflowRun(
                    workflowRun.id(),
                    workflowRun.projectId(),
                    workflowRun.sourceWorkflowId(),
                    workflowRun.taskId(),
                    workflowRun.workflowName(),
                    workflowRun.input(),
                    WorkflowRunStatus.RUNNING,
                    workflowRun.nodeRuns(),
                    workflowRun.createdAt(),
                    workflowRun.startedAt() == null ? now : workflowRun.startedAt(),
                    workflowRun.finishedAt()
            ));
        }

        final NodeExecutionInputContent input = this.inputContentPolicyRegistry.assemble(new NodeInputContentContext(
                workflowRun,
                running,
                this.resolutionRepository.findConsumedByNodeRunId(running.id())
        ));
        return Optional.of(new NodeExecutionClaim(
                workflowRun.id(),
                running.id(),
                running.sourceAgentId(),
                input.workflowInput(),
                running.agentName(),
                running.agentInstructions(),
                running.agentOutputSchema(),
                running.executionModel(),
                running.inputMode(),
                input.dependencies()
        ));
    }

    @Transactional
    public void succeed(final UUID nodeRunId, final NodeRunOutput output) {
        if (output == null) {
            throw new ConflictException(LIFECYCLE_CONFLICT, "Node run success output is required.");
        }
        final CompletionTarget target = this.lockCompletionTarget(nodeRunId);
        if (target == null) {
            return;
        }
        final NodeRun nodeRun = target.nodeRun();
        if (nodeRun.status() == NodeRunStatus.SUCCEEDED && output.equals(nodeRun.output())) {
            return;
        }
        if (this.isTerminal(nodeRun.status())) {
            throw this.conflict("Node run already has a different terminal outcome.");
        }
        if (nodeRun.status() != NodeRunStatus.RUNNING) {
            throw this.conflict("Only RUNNING node runs can succeed.");
        }
        this.nodeRunRepository.save(this.withSucceeded(nodeRun, output, Instant.now(this.clock)));
        this.coordinator.completeSuccessfulNodeRun(nodeRunId);
    }

    @Transactional
    public void fail(final UUID nodeRunId, final NodeRunFailure failure) {
        final NodeRunFailure normalized = this.normalizeFailure(failure);
        final CompletionTarget target = this.lockCompletionTarget(nodeRunId);
        if (target == null) {
            return;
        }
        final NodeRun nodeRun = target.nodeRun();
        if (nodeRun.status() == NodeRunStatus.FAILED && normalized.equals(nodeRun.failure())) {
            return;
        }
        if (this.isTerminal(nodeRun.status())) {
            throw this.conflict("Node run already has a different terminal outcome.");
        }
        if (nodeRun.status() != NodeRunStatus.RUNNING) {
            throw this.conflict("Only RUNNING node runs can fail.");
        }
        this.nodeRunRepository.save(this.withFailed(nodeRun, normalized, Instant.now(this.clock)));
        this.reconcileWorkflowRun(target.workflowRun(), Instant.now(this.clock));
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

    private void reconcileWorkflowRun(final WorkflowRun lockedWorkflowRun, final Instant now) {
        if (lockedWorkflowRun.finishedAt() != null || this.isTerminal(lockedWorkflowRun.status())) {
            return;
        }
        final List<NodeRun> nodeRuns = this.nodeRunRepository.findByWorkflowRunId(lockedWorkflowRun.id());
        if (nodeRuns.stream().anyMatch(nodeRun -> nodeRun.status() == NodeRunStatus.PENDING || nodeRun.status() == NodeRunStatus.RUNNING)) {
            return;
        }
        final boolean allSucceeded = nodeRuns.stream().allMatch(nodeRun -> nodeRun.status() == NodeRunStatus.SUCCEEDED);
        this.workflowRunRepository.saveLifecycle(new WorkflowRun(
                lockedWorkflowRun.id(),
                lockedWorkflowRun.projectId(),
                lockedWorkflowRun.sourceWorkflowId(),
                lockedWorkflowRun.taskId(),
                lockedWorkflowRun.workflowName(),
                lockedWorkflowRun.input(),
                allSucceeded ? WorkflowRunStatus.SUCCEEDED : WorkflowRunStatus.FAILED,
                lockedWorkflowRun.nodeRuns(),
                lockedWorkflowRun.createdAt(),
                lockedWorkflowRun.startedAt(),
                lockedWorkflowRun.finishedAt() == null ? now : lockedWorkflowRun.finishedAt()
        ));
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

    private NodeRunFailure normalizeFailure(final NodeRunFailure failure) {
        if (failure == null || this.isBlank(failure.code())) {
            return new NodeRunFailure("NODE_RUN_FAILED", "Node run failed.");
        }
        return new NodeRunFailure(
                failure.code(),
                this.isBlank(failure.message()) ? "Node run failed." : failure.message()
        );
    }

    private boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }

    private NodeRun withRunning(final NodeRun nodeRun, final Instant now) {
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
                NodeRunStatus.RUNNING,
                nodeRun.output(),
                nodeRun.failure(),
                nodeRun.executionModel(),
                nodeRun.createdAt(),
                nodeRun.startedAt() == null ? now : nodeRun.startedAt(),
                nodeRun.finishedAt()
        );
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
        return new ConflictException(LIFECYCLE_CONFLICT, message);
    }

    private record CompletionTarget(WorkflowRun workflowRun, NodeRun nodeRun) {
    }
}
