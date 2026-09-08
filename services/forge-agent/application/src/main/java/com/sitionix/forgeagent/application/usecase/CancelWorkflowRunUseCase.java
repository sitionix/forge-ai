package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.application.runtime.AgentExecutor;
import com.sitionix.forgeagent.application.runtime.WorkflowExecutionCoordinator;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class CancelWorkflowRunUseCase {

    public static final String INTERRUPT_UNAVAILABLE = "AGENT_EXECUTION_INTERRUPT_UNAVAILABLE";
    public static final String NOT_CANCELLABLE = "WORKFLOW_RUN_NOT_CANCELLABLE";

    private final WorkflowRunRepository workflowRunRepository;
    private final NodeRunRepository nodeRunRepository;
    private final WorkflowExecutionCoordinator coordinator;
    private final AgentExecutor agentExecutor;
    private final Clock clock;

    @Transactional
    public void execute(final UUID workflowRunId) {
        final WorkflowRun run = this.workflowRunRepository.findByIdForUpdate(workflowRunId)
                .orElseThrow(() -> new NotFoundException("WORKFLOW_RUN_NOT_FOUND", "Workflow run was not found."));
        if (run.status() == WorkflowRunStatus.CANCELLED) {
            return;
        }
        if (run.status() == WorkflowRunStatus.SUCCEEDED || run.status() == WorkflowRunStatus.FAILED) {
            throw new ConflictException(NOT_CANCELLABLE, "The workflow run has already finished and cannot be stopped.");
        }

        final List<NodeRun> active = this.nodeRunRepository.findByWorkflowRunId(workflowRunId).stream()
                .filter(nodeRun -> nodeRun.status() == NodeRunStatus.PENDING
                        || nodeRun.status() == NodeRunStatus.RUNNING)
                .toList();
        final List<Runnable> cancellations = new ArrayList<>();
        for (final NodeRun nodeRun : active) {
            if (nodeRun.status() != NodeRunStatus.RUNNING) {
                continue;
            }
            cancellations.add(this.agentExecutor.secureCancellation(nodeRun.id())
                    .orElseThrow(() -> new ConflictException(
                            INTERRUPT_UNAVAILABLE,
                            "The active agent execution cannot be interrupted safely. Try again after its state changes."
                    )));
        }

        this.coordinator.cancelActiveNodeRuns(run);
        this.workflowRunRepository.saveLifecycle(this.cancelled(run));
        this.afterCommit(cancellations);
    }

    private void afterCommit(final List<Runnable> cancellations) {
        if (cancellations.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Workflow run cancellation requires transaction synchronization.");
        }
        final List<Runnable> secured = List.copyOf(cancellations);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                secured.forEach(CancelWorkflowRunUseCase.this::invokeCancellation);
            }
        });
    }

    private void invokeCancellation(final Runnable cancellation) {
        try {
            cancellation.run();
        } catch (final RuntimeException exception) {
            log.warn("Committed workflow cancellation action failed", exception);
        }
    }

    private WorkflowRun cancelled(final WorkflowRun run) {
        return new WorkflowRun(
                run.id(), run.projectId(), run.sourceWorkflowId(), run.taskId(), run.workflowName(), run.input(),
                WorkflowRunStatus.CANCELLED, run.nodeRuns(), run.connectionResolutions(), run.executionEdges(),
                run.runtimeGraph(), run.result(), run.resultSourceNodeRunId(), run.createdAt(), run.startedAt(),
                run.finishedAt() == null ? Instant.now(this.clock) : run.finishedAt(), run.repositoryIds()
        );
    }
}
