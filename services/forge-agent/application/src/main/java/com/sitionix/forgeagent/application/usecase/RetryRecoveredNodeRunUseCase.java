package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.model.ConnectionResolution;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.RecoveredNodeRunRetryEligibility;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.ConnectionResolutionRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RetryRecoveredNodeRunUseCase {

    private final WorkflowRunRepository workflows;
    private final NodeRunRepository nodeRuns;
    private final ConnectionResolutionRepository resolutions;
    private final RecoveredNodeRunRetryEligibilityService eligibility;
    private final Clock clock;

    @Transactional
    public RetryRecoveredNodeRunResult execute(final UUID workflowRunId, final UUID nodeRunId) {
        final WorkflowRun workflowRun = this.workflows.findByIdForUpdate(workflowRunId)
                .orElseThrow(() -> new NotFoundException("WORKFLOW_RUN_NOT_FOUND", "Workflow run was not found."));
        final NodeRun target = this.nodeRuns.findByIdForUpdate(nodeRunId)
                .orElseThrow(() -> new NotFoundException("NODE_RUN_NOT_FOUND", "Node run was not found."));
        if (!workflowRunId.equals(target.workflowRunId())) {
            throw new NotFoundException("NODE_RUN_NOT_FOUND", "Node run was not found in the workflow run.");
        }

        final var existing = this.nodeRuns.findRetryChild(target.id());
        if (existing.isPresent()) {
            return new RetryRecoveredNodeRunResult(existing.get().id(), this.fullWorkflowRun(workflowRun));
        }

        final List<NodeRun> attempts = this.nodeRuns.findByWorkflowRunId(workflowRunId);
        final RecoveredNodeRunRetryEligibility decision = this.eligibility.evaluate(workflowRun, target, attempts);
        if (!decision.eligible()) {
            throw new ConflictException(decision.reasonCode(), this.message(decision.reasonCode()));
        }

        final NodeRun child = this.nodeRuns.saveAndFlush(this.retryOf(target));
        this.copyConsumedInputs(target.id(), child.id());
        final WorkflowRun reopened = this.workflows.reopenForRetry(this.reopen(workflowRun));
        return new RetryRecoveredNodeRunResult(child.id(), this.fullWorkflowRun(reopened));
    }

    private void copyConsumedInputs(final UUID parentNodeRunId, final UUID childNodeRunId) {
        final Instant copiedAt = Instant.now(this.clock);
        final List<ConnectionResolution> parents = this.resolutions.findConsumedByNodeRunId(parentNodeRunId);
        final List<ConnectionResolution> copies = IntStream.range(0, parents.size())
                .mapToObj(index -> {
                    final ConnectionResolution parent = parents.get(index);
                    return new ConnectionResolution(UUID.randomUUID(), parent.workflowRunId(),
                        parent.executionFrameId(), parent.sourceNodeRunId(), parent.sourceConnectionId(),
                        parent.targetInputPortId(), parent.type(), parent.payload(), childNodeRunId,
                        copiedAt.plusNanos(index * 1_000L), parent.targetRepositoryId());
                })
                .toList();
        if (!copies.isEmpty()) {
            this.resolutions.saveAll(copies);
        }
    }

    private WorkflowRun fullWorkflowRun(final WorkflowRun fallback) {
        return this.workflows.findById(fallback.id()).orElse(fallback);
    }

    private NodeRun retryOf(final NodeRun target) {
        return new NodeRun(UUID.randomUUID(), target.workflowRunId(), target.sourceNodeId(), target.sourceAgentId(),
                target.agentName(), target.agentInstructions(), target.agentOutputSchema(), target.inputMode(),
                target.position(), target.executionFrameId(), target.enteredViaInputPortId(), target.activationFrameId(),
                null, null, NodeRunStatus.PENDING, null, null, target.executionModel(), Instant.now(this.clock),
                null, null, target.repositoryId(), target.contextMode(), target.contextTrackingVersion(), target.id(), target.contextGroupKey(), target.contextIterationId());
    }

    private WorkflowRun reopen(final WorkflowRun run) {
        return new WorkflowRun(run.id(), run.projectId(), run.sourceWorkflowId(), run.taskId(), run.workflowName(),
                run.input(), WorkflowRunStatus.RUNNING, run.nodeRuns(), run.connectionResolutions(),
                run.executionEdges(), run.runtimeGraph(), null, null, run.createdAt(), run.startedAt(), null,
                run.repositoryIds(), run.operatorStopStatus(), run.operatorStopFailureCode(),
                run.operatorStopPendingNodeRunIds(), run.operatorStopAttempt());
    }

    private String message(final String code) {
        return switch (code) {
            case RecoveredNodeRunRetryEligibilityService.SUPERSEDED ->
                    "The selected node run was already superseded by a retry attempt.";
            case RecoveredNodeRunRetryEligibilityService.UNSAFE ->
                    "The workflow contains cancelled parallel work that cannot be reconstructed safely.";
            case RecoveredNodeRunRetryEligibilityService.CONTEXT_UNSAFE ->
                    "The reusable agent context is not in a proven safe state for resume.";
            default -> "The selected recovery failure is not eligible for manual retry.";
        };
    }
}
