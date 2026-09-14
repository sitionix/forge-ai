package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.application.runtime.AgentExecutor;
import com.sitionix.forgeagent.application.runtime.WorkflowExecutionCoordinator;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.OperatorStopStatus;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Slf4j
@Service
@RequiredArgsConstructor
public class CancelWorkflowRunUseCase {

    public static final String INTERRUPT_UNAVAILABLE = "AGENT_EXECUTION_INTERRUPT_UNAVAILABLE";
    public static final String INTERRUPT_FAILED = "AGENT_EXECUTION_INTERRUPT_FAILED";
    public static final String NOT_CANCELLABLE = "WORKFLOW_RUN_NOT_CANCELLABLE";
    public static final String CANCELLATION_CONFLICT = "WORKFLOW_RUN_CANCELLATION_CONFLICT";

    private final WorkflowRunRepository workflowRunRepository;
    private final NodeRunRepository nodeRunRepository;
    private final WorkflowExecutionCoordinator coordinator;
    private final AgentExecutor agentExecutor;
    private final Clock clock;
    private final TransactionOperations transactions;

    public void execute(final UUID workflowRunId) {
        final StopAttempt attempt = this.transactions.execute(status -> this.prepare(workflowRunId));
        if (attempt == null || attempt.resolved()) {
            return;
        }

        final List<UUID> failedNodeRunIds = new ArrayList<>();
        for (final CancellationAction cancellation : attempt.cancellations()) {
            try {
                cancellation.action().run();
            } catch (final RuntimeException exception) {
                failedNodeRunIds.add(cancellation.nodeRunId());
                log.warn("Committed workflow provider cancellation failed workflowRunId={} nodeRunId={}",
                        workflowRunId, cancellation.nodeRunId(), exception);
            }
        }

        this.transactions.executeWithoutResult(status ->
                this.recordOutcome(workflowRunId, attempt.attempt(), failedNodeRunIds));
        if (!failedNodeRunIds.isEmpty()) {
            throw new ConflictException(
                    INTERRUPT_FAILED,
                    "The workflow run was cancelled, but provider interruption could not be verified. Retry stop."
            );
        }
    }

    private StopAttempt prepare(final UUID workflowRunId) {
        final WorkflowRun run = this.workflowRunRepository.findByIdForUpdate(workflowRunId)
                .orElseThrow(() -> new NotFoundException("WORKFLOW_RUN_NOT_FOUND", "Workflow run was not found."));
        if (run.status() == WorkflowRunStatus.CANCELLED) {
            return this.prepareRetry(run);
        }
        if (run.status() == WorkflowRunStatus.SUCCEEDED || run.status() == WorkflowRunStatus.FAILED) {
            throw new ConflictException(NOT_CANCELLABLE, "The workflow run has already finished and cannot be stopped.");
        }

        final List<NodeRun> active = this.nodeRunRepository.findByWorkflowRunId(workflowRunId).stream()
                .filter(nodeRun -> nodeRun.status() == NodeRunStatus.PENDING
                        || nodeRun.status() == NodeRunStatus.RUNNING)
                .toList();
        final List<CancellationAction> cancellations = new ArrayList<>();
        for (final NodeRun nodeRun : active) {
            if (nodeRun.status() != NodeRunStatus.RUNNING) {
                continue;
            }
            cancellations.add(new CancellationAction(
                    nodeRun.id(),
                    this.agentExecutor.secureCancellation(nodeRun.id())
                            .orElseThrow(this::interruptUnavailable)
            ));
        }

        if (!this.coordinator.cancelActiveNodeRuns(run)) {
            throw new ConflictException(
                    CANCELLATION_CONFLICT,
                    "The workflow run changed while it was being stopped. No cancellation was committed."
            );
        }
        final long attempt = run.operatorStopAttempt() + 1;
        this.workflowRunRepository.saveLifecycle(this.withOperatorStop(
                this.cancelled(run),
                OperatorStopStatus.PENDING,
                null,
                cancellations.stream().map(CancellationAction::nodeRunId).toList(),
                attempt
        ));
        return new StopAttempt(false, attempt, List.copyOf(cancellations));
    }

    private StopAttempt prepareRetry(final WorkflowRun run) {
        if (run.operatorStopStatus() == null || run.operatorStopStatus() == OperatorStopStatus.COMPLETE) {
            return StopAttempt.resolvedAttempt();
        }
        if (run.operatorStopPendingNodeRunIds().isEmpty()) {
            throw this.interruptUnavailable();
        }

        final List<CancellationAction> cancellations = run.operatorStopPendingNodeRunIds().stream()
                .map(nodeRunId -> new CancellationAction(
                        nodeRunId,
                        this.agentExecutor.secureCancellation(nodeRunId)
                                .orElseThrow(this::interruptUnavailable)
                ))
                .toList();
        final long attempt = run.operatorStopAttempt() + 1;
        this.workflowRunRepository.saveLifecycle(this.withOperatorStop(
                run,
                OperatorStopStatus.PENDING,
                null,
                run.operatorStopPendingNodeRunIds(),
                attempt
        ));
        return new StopAttempt(false, attempt, cancellations);
    }

    private void recordOutcome(final UUID workflowRunId, final long attempt, final List<UUID> failedNodeRunIds) {
        final WorkflowRun run = this.workflowRunRepository.findByIdForUpdate(workflowRunId)
                .orElseThrow(() -> new NotFoundException("WORKFLOW_RUN_NOT_FOUND", "Workflow run was not found."));
        if (run.status() != WorkflowRunStatus.CANCELLED
                || run.operatorStopStatus() != OperatorStopStatus.PENDING
                || run.operatorStopAttempt() != attempt) {
            return;
        }
        this.workflowRunRepository.saveLifecycle(this.withOperatorStop(
                run,
                failedNodeRunIds.isEmpty() ? OperatorStopStatus.COMPLETE : OperatorStopStatus.FAILED,
                failedNodeRunIds.isEmpty() ? null : INTERRUPT_FAILED,
                failedNodeRunIds,
                attempt
        ));
    }

    private ConflictException interruptUnavailable() {
        return new ConflictException(
                INTERRUPT_UNAVAILABLE,
                "The provider interruption handle is no longer available."
        );
    }

    private WorkflowRun cancelled(final WorkflowRun run) {
        return new WorkflowRun(
                run.id(), run.projectId(), run.sourceWorkflowId(), run.taskId(), run.workflowName(), run.input(),
                WorkflowRunStatus.CANCELLED, run.nodeRuns(), run.connectionResolutions(), run.executionEdges(),
                run.runtimeGraph(), run.result(), run.resultSourceNodeRunId(), run.createdAt(), run.startedAt(),
                run.finishedAt() == null ? Instant.now(this.clock) : run.finishedAt(), run.repositoryIds(),
                run.operatorStopStatus(), run.operatorStopFailureCode(), run.operatorStopPendingNodeRunIds(),
                run.operatorStopAttempt()
        );
    }

    private WorkflowRun withOperatorStop(final WorkflowRun run, final OperatorStopStatus status,
                                         final String failureCode, final List<UUID> pendingNodeRunIds,
                                         final long attempt) {
        return new WorkflowRun(
                run.id(), run.projectId(), run.sourceWorkflowId(), run.taskId(), run.workflowName(), run.input(),
                run.status(), run.nodeRuns(), run.connectionResolutions(), run.executionEdges(), run.runtimeGraph(),
                run.result(), run.resultSourceNodeRunId(), run.createdAt(), run.startedAt(), run.finishedAt(),
                run.repositoryIds(), status, failureCode, pendingNodeRunIds, attempt
        );
    }

    private record CancellationAction(UUID nodeRunId, Runnable action) {
    }

    private record StopAttempt(boolean resolved, long attempt, List<CancellationAction> cancellations) {
        private static StopAttempt resolvedAttempt() {
            return new StopAttempt(true, 0, List.of());
        }
    }
}
