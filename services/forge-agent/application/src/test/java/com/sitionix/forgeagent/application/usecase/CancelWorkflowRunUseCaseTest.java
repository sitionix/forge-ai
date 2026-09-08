package com.sitionix.forgeagent.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.application.runtime.AgentExecutor;
import com.sitionix.forgeagent.application.runtime.WorkflowExecutionCoordinator;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.NodeContextMode;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CancelWorkflowRunUseCaseTest {

    private static final UUID RUN_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");

    private final WorkflowRunRepository workflowRuns = mock(WorkflowRunRepository.class);
    private final NodeRunRepository nodeRuns = mock(NodeRunRepository.class);
    private final WorkflowExecutionCoordinator coordinator = mock(WorkflowExecutionCoordinator.class);
    private final AgentExecutor executor = mock(AgentExecutor.class);
    private final CancelWorkflowRunUseCase useCase = new CancelWorkflowRunUseCase(
            this.workflowRuns, this.nodeRuns, this.coordinator, this.executor,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void startTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearTransactionSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void cancelsAllWorkAndInvokesSecuredExecutionOnlyAfterCommit() {
        final WorkflowRun run = this.run(WorkflowRunStatus.RUNNING, null);
        final NodeRun running = this.nodeRun(NodeRunStatus.RUNNING, 1);
        final NodeRun pending = this.nodeRun(NodeRunStatus.PENDING, 1);
        final AtomicInteger cancellations = new AtomicInteger();
        when(this.workflowRuns.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(run));
        when(this.nodeRuns.findByWorkflowRunId(RUN_ID)).thenReturn(List.of(running, pending));
        when(this.executor.secureCancellation(running.id())).thenReturn(Optional.of(cancellations::incrementAndGet));
        when(this.coordinator.cancelActiveNodeRuns(run)).thenReturn(true);

        this.useCase.execute(RUN_ID);

        assertThat(cancellations).hasValue(0);
        verify(this.coordinator).cancelActiveNodeRuns(run);
        verify(this.workflowRuns).saveLifecycle(org.mockito.ArgumentMatchers.argThat(saved ->
                saved.status() == WorkflowRunStatus.CANCELLED && NOW.equals(saved.finishedAt())));
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(cancellations).hasValue(1);
    }

    @Test
    void failsClosedWhenAuthoritativeLifecycleCancellationCannotTransitionEveryNode() {
        final WorkflowRun run = this.run(WorkflowRunStatus.RUNNING, null);
        final NodeRun running = this.nodeRun(NodeRunStatus.RUNNING, 1);
        final AtomicInteger cancellations = new AtomicInteger();
        when(this.workflowRuns.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(run));
        when(this.nodeRuns.findByWorkflowRunId(RUN_ID)).thenReturn(List.of(running));
        when(this.executor.secureCancellation(running.id())).thenReturn(Optional.of(cancellations::incrementAndGet));
        when(this.coordinator.cancelActiveNodeRuns(run)).thenReturn(false);

        assertThatThrownBy(() -> this.useCase.execute(RUN_ID))
                .isInstanceOfSatisfying(ConflictException.class, failure ->
                        assertThat(failure.code()).isEqualTo("WORKFLOW_RUN_CANCELLATION_CONFLICT"));

        verify(this.workflowRuns, never()).saveLifecycle(org.mockito.ArgumentMatchers.any());
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        assertThat(cancellations).hasValue(0);
    }

    @Test
    void failsClosedBeforePersistenceWhenRunningExecutionHasNoHandle() {
        final WorkflowRun run = this.run(WorkflowRunStatus.RUNNING, null);
        final NodeRun running = this.nodeRun(NodeRunStatus.RUNNING, 1);
        when(this.workflowRuns.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(run));
        when(this.nodeRuns.findByWorkflowRunId(RUN_ID)).thenReturn(List.of(running));
        when(this.executor.secureCancellation(running.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.useCase.execute(RUN_ID))
                .isInstanceOfSatisfying(ConflictException.class, failure ->
                        assertThat(failure.code()).isEqualTo("AGENT_EXECUTION_INTERRUPT_UNAVAILABLE"));

        verify(this.coordinator, never()).cancelActiveNodeRuns(run);
        verify(this.workflowRuns, never()).saveLifecycle(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void alreadyCancelledRunIsAnIdempotentSuccess() {
        final WorkflowRun cancelled = this.run(WorkflowRunStatus.CANCELLED, NOW.minusSeconds(1));
        when(this.workflowRuns.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(cancelled));

        this.useCase.execute(RUN_ID);

        verify(this.nodeRuns, never()).findByWorkflowRunId(RUN_ID);
        verify(this.coordinator, never()).cancelActiveNodeRuns(cancelled);
    }

    @Test
    void naturalTerminalOutcomeIsNeverRewritten() {
        final WorkflowRun succeeded = this.run(WorkflowRunStatus.SUCCEEDED, NOW.minusSeconds(1));
        when(this.workflowRuns.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(succeeded));

        assertThatThrownBy(() -> this.useCase.execute(RUN_ID))
                .isInstanceOfSatisfying(ConflictException.class, failure ->
                        assertThat(failure.code()).isEqualTo("WORKFLOW_RUN_NOT_CANCELLABLE"));

        verify(this.workflowRuns, never()).saveLifecycle(org.mockito.ArgumentMatchers.any());
    }

    private WorkflowRun run(final WorkflowRunStatus status, final Instant finishedAt) {
        return new WorkflowRun(RUN_ID, UUID.randomUUID(), UUID.randomUUID(), null, "Workflow", "input",
                status, List.of(), List.of(), List.of(), null, null, null, NOW.minusSeconds(10),
                status == WorkflowRunStatus.QUEUED ? null : NOW.minusSeconds(9), finishedAt, List.of());
    }

    private NodeRun nodeRun(final NodeRunStatus status, final Integer trackingVersion) {
        return new NodeRun(UUID.randomUUID(), RUN_ID, UUID.randomUUID(), UUID.randomUUID(), "Agent", "Do work",
                null, null, null, UUID.randomUUID(), null, null, null, null, status, null, null,
                new NodeRunExecutionModel("codex", "gpt-5", null), NOW.minusSeconds(8),
                status == NodeRunStatus.PENDING ? null : NOW.minusSeconds(7), null, null,
                NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE, trackingVersion);
    }
}
