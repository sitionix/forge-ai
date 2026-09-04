package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunFailure;
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
import org.springframework.beans.factory.annotation.Autowired;
import com.sitionix.forgeagent.domain.model.AgentSessionExecutionClaim;
import com.sitionix.forgeagent.domain.model.AgentExecutionTurnStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NodeRunCompletionPersistence {

    private final NodeRunRepository nodeRunRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowCompletionPolicy completionPolicy;
    private final WorkflowExecutionCoordinator coordinator;
    private final Clock clock;
    private final AgentSessionLeaseService sessionLeaseService;

    @Autowired
    public NodeRunCompletionPersistence(NodeRunRepository nodeRunRepository, WorkflowRunRepository workflowRunRepository,
                                        WorkflowCompletionPolicy completionPolicy, WorkflowExecutionCoordinator coordinator,
                                        Clock clock, AgentSessionLeaseService sessionLeaseService) {
        this.nodeRunRepository=nodeRunRepository; this.workflowRunRepository=workflowRunRepository;
        this.completionPolicy=completionPolicy; this.coordinator=coordinator; this.clock=clock;
        this.sessionLeaseService=sessionLeaseService;
    }

    NodeRunCompletionPersistence(NodeRunRepository nodeRunRepository, WorkflowRunRepository workflowRunRepository,
                                 WorkflowCompletionPolicy completionPolicy, WorkflowExecutionCoordinator coordinator,
                                 Clock clock) {
        this(nodeRunRepository, workflowRunRepository, completionPolicy, coordinator, clock, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markBusinessSucceeded(final UUID nodeRunId, final AgentExecutionResult result) {
        return this.markBusinessSucceeded(nodeRunId, result, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markBusinessSucceeded(final UUID nodeRunId, final AgentExecutionResult result,
                                         final AgentSessionExecutionClaim claim) {
        final CompletionTarget target = this.lockCompletionTarget(nodeRunId);
        if (target == null) {
            return false;
        }
        if (claim != null) this.sessionLeaseService.lockCurrent(claim);
        final NodeRun nodeRun = target.nodeRun();
        if (nodeRun.contextTrackingVersion() != null && claim == null) {
            throw new ConflictException(
                    "STALE_AGENT_SESSION_LEASE",
                    "Tracked agent execution completion requires the current session lease."
            );
        }
        if (nodeRun.status() == NodeRunStatus.CANCELLED && this.isTerminal(target.workflowRun().status())) {
            return false;
        }
        if (nodeRun.status() == NodeRunStatus.SUCCEEDED
                && result.output().equals(nodeRun.output())
                && java.util.Objects.equals(result.selectedOutputPortId(), nodeRun.selectedOutputPortId())) {
            return false;
        }
        if (this.isTerminal(nodeRun.status())) {
            throw this.conflict("Node run already has a different terminal outcome.");
        }
        if (nodeRun.status() != NodeRunStatus.RUNNING) {
            throw this.conflict("Only RUNNING node runs can succeed.");
        }
        this.nodeRunRepository.saveAndFlush(this.withSucceeded(nodeRun, result, Instant.now(this.clock)));
        if (claim != null) this.sessionLeaseService.finish(claim, AgentExecutionTurnStatus.SUCCEEDED, null, null, false);
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

    private NodeRun withSucceeded(final NodeRun nodeRun, final AgentExecutionResult result, final Instant now) {
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
                result.selectedOutputPortId(),
                nodeRun.routingCompletedAt(),
                NodeRunStatus.SUCCEEDED,
                result.output(),
                nodeRun.failure(),
                nodeRun.executionModel(),
                nodeRun.createdAt(),
                nodeRun.startedAt(),
                nodeRun.finishedAt() == null ? now : nodeRun.finishedAt(),
                nodeRun.repositoryId(),
                nodeRun.contextMode(),
                nodeRun.contextTrackingVersion()
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
                nodeRun.routingCompletedAt(),
                NodeRunStatus.FAILED,
                nodeRun.output(),
                failure,
                nodeRun.executionModel(),
                nodeRun.createdAt(),
                nodeRun.startedAt(),
                nodeRun.finishedAt() == null ? now : nodeRun.finishedAt(),
                nodeRun.repositoryId(),
                nodeRun.contextMode(),
                nodeRun.contextTrackingVersion()
        );
    }

    private ConflictException conflict(final String message) {
        return new ConflictException(NodeRunLifecycle.LIFECYCLE_CONFLICT, message);
    }

    private record CompletionTarget(WorkflowRun workflowRun, NodeRun nodeRun) {
    }
}
