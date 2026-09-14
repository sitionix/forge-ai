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
import com.sitionix.forgeagent.domain.model.OperatorStopStatus;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class CancelWorkflowRunUseCaseTest {

    private static final UUID RUN_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");

    private final WorkflowRunRepository workflowRuns = mock(WorkflowRunRepository.class);
    private final NodeRunRepository nodeRuns = mock(NodeRunRepository.class);
    private final WorkflowExecutionCoordinator coordinator = mock(WorkflowExecutionCoordinator.class);
    private final AgentExecutor executor = mock(AgentExecutor.class);
    private final AtomicReference<WorkflowRun> persisted = new AtomicReference<>();
    private final TransactionOperations transactions = new TransactionOperations() {
        @Override
        public <T> T execute(final TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    };
    private final CancelWorkflowRunUseCase useCase = new CancelWorkflowRunUseCase(
            this.workflowRuns, this.nodeRuns, this.coordinator, this.executor,
            Clock.fixed(NOW, ZoneOffset.UTC), this.transactions);

    @BeforeEach
    void persistLifecycleState() {
        when(this.workflowRuns.findByIdForUpdate(RUN_ID))
                .thenAnswer(ignored -> Optional.ofNullable(this.persisted.get()));
        when(this.workflowRuns.saveLifecycle(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            final WorkflowRun saved = invocation.getArgument(0);
            this.persisted.set(saved);
            return saved;
        });
    }

    @Test
    void providerCancellationSuccessCompletesDurableOperatorStop() {
        final WorkflowRun run = this.run(WorkflowRunStatus.RUNNING, null, null, List.of(), 0);
        final NodeRun running = this.nodeRun(NodeRunStatus.RUNNING);
        final AtomicInteger cancellations = new AtomicInteger();
        this.persisted.set(run);
        when(this.nodeRuns.findByWorkflowRunId(RUN_ID)).thenReturn(List.of(running));
        when(this.executor.secureCancellation(running.id())).thenReturn(Optional.of(() -> {
            assertThat(this.persisted.get().operatorStopStatus()).isEqualTo(OperatorStopStatus.PENDING);
            cancellations.incrementAndGet();
        }));
        when(this.coordinator.cancelActiveNodeRuns(run)).thenReturn(true);

        this.useCase.execute(RUN_ID);

        assertThat(cancellations).hasValue(1);
        assertThat(this.persisted.get().status()).isEqualTo(WorkflowRunStatus.CANCELLED);
        assertThat(this.persisted.get().operatorStopStatus()).isEqualTo(OperatorStopStatus.COMPLETE);
        assertThat(this.persisted.get().operatorStopFailureCode()).isNull();
        assertThat(this.persisted.get().operatorStopPendingNodeRunIds()).isEmpty();
    }

    @Test
    void providerCancellationFailureAttemptsAllActionsAndPersistsTypedFailure() {
        final WorkflowRun run = this.run(WorkflowRunStatus.RUNNING, null, null, List.of(), 0);
        final NodeRun first = this.nodeRun(NodeRunStatus.RUNNING);
        final NodeRun second = this.nodeRun(NodeRunStatus.RUNNING);
        final AtomicInteger secondAttempts = new AtomicInteger();
        this.persisted.set(run);
        when(this.nodeRuns.findByWorkflowRunId(RUN_ID)).thenReturn(List.of(first, second));
        when(this.executor.secureCancellation(first.id())).thenReturn(Optional.of(() -> {
            throw new IllegalStateException("cleanup failed");
        }));
        when(this.executor.secureCancellation(second.id())).thenReturn(Optional.of(secondAttempts::incrementAndGet));
        when(this.coordinator.cancelActiveNodeRuns(run)).thenReturn(true);

        assertThatThrownBy(() -> this.useCase.execute(RUN_ID))
                .isInstanceOfSatisfying(ConflictException.class, failure ->
                        assertThat(failure.code()).isEqualTo("AGENT_EXECUTION_INTERRUPT_FAILED"));

        assertThat(secondAttempts).hasValue(1);
        assertThat(this.persisted.get().status()).isEqualTo(WorkflowRunStatus.CANCELLED);
        assertThat(this.persisted.get().operatorStopStatus()).isEqualTo(OperatorStopStatus.FAILED);
        assertThat(this.persisted.get().operatorStopFailureCode())
                .isEqualTo("AGENT_EXECUTION_INTERRUPT_FAILED");
        assertThat(this.persisted.get().operatorStopPendingNodeRunIds()).containsExactly(first.id());
    }

    @Test
    void failedProviderCancellationCanBeRetriedSuccessfully() {
        final WorkflowRun run = this.run(WorkflowRunStatus.RUNNING, null, null, List.of(), 0);
        final NodeRun running = this.nodeRun(NodeRunStatus.RUNNING);
        final AtomicInteger attempts = new AtomicInteger();
        final Runnable cancellation = () -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("cleanup failed");
        };
        this.persisted.set(run);
        when(this.nodeRuns.findByWorkflowRunId(RUN_ID)).thenReturn(List.of(running));
        when(this.executor.secureCancellation(running.id())).thenReturn(Optional.of(cancellation));
        when(this.coordinator.cancelActiveNodeRuns(run)).thenReturn(true);

        assertThatThrownBy(() -> this.useCase.execute(RUN_ID)).isInstanceOf(ConflictException.class);
        this.useCase.execute(RUN_ID);

        assertThat(attempts).hasValue(2);
        assertThat(this.persisted.get().operatorStopStatus()).isEqualTo(OperatorStopStatus.COMPLETE);
        assertThat(this.persisted.get().operatorStopPendingNodeRunIds()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = OperatorStopStatus.class, names = { "PENDING", "FAILED" })
    void unresolvedCancelledRunRetriesItsExistingProviderHandle(final OperatorStopStatus status) {
        final UUID pendingNodeRunId = UUID.randomUUID();
        final WorkflowRun cancelled = this.run(WorkflowRunStatus.CANCELLED, status,
                status == OperatorStopStatus.FAILED ? "AGENT_EXECUTION_INTERRUPT_FAILED" : null,
                List.of(pendingNodeRunId), 4);
        final AtomicInteger cancellations = new AtomicInteger();
        this.persisted.set(cancelled);
        when(this.executor.secureCancellation(pendingNodeRunId))
                .thenReturn(Optional.of(cancellations::incrementAndGet));

        this.useCase.execute(RUN_ID);

        assertThat(cancellations).hasValue(1);
        assertThat(this.persisted.get().operatorStopStatus()).isEqualTo(OperatorStopStatus.COMPLETE);
        assertThat(this.persisted.get().operatorStopFailureCode()).isNull();
        assertThat(this.persisted.get().operatorStopPendingNodeRunIds()).isEmpty();
        assertThat(this.persisted.get().operatorStopAttempt()).isEqualTo(5);
    }

    @ParameterizedTest
    @EnumSource(value = OperatorStopStatus.class, names = { "PENDING", "FAILED" })
    void unresolvedCancelledRunWithoutLocalHandleFailsClosed(final OperatorStopStatus status) {
        final UUID pendingNodeRunId = UUID.randomUUID();
        final WorkflowRun cancelled = this.run(WorkflowRunStatus.CANCELLED, status,
                status == OperatorStopStatus.FAILED ? "AGENT_EXECUTION_INTERRUPT_FAILED" : null,
                List.of(pendingNodeRunId), 4);
        this.persisted.set(cancelled);
        when(this.executor.secureCancellation(pendingNodeRunId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.useCase.execute(RUN_ID))
                .isInstanceOfSatisfying(ConflictException.class, failure ->
                        assertThat(failure.code()).isEqualTo("AGENT_EXECUTION_INTERRUPT_UNAVAILABLE"));

        assertThat(this.persisted.get()).isEqualTo(cancelled);
        verify(this.coordinator, never()).cancelActiveNodeRuns(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void completedOperatorStopIsIdempotent() {
        final WorkflowRun cancelled = this.run(
                WorkflowRunStatus.CANCELLED, OperatorStopStatus.COMPLETE, null, List.of(), 2);
        this.persisted.set(cancelled);

        this.useCase.execute(RUN_ID);

        verify(this.executor, never()).secureCancellation(org.mockito.ArgumentMatchers.any());
        verify(this.workflowRuns, never()).saveLifecycle(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void historicalCancellationWithoutOperatorMetadataIsIdempotent() {
        final WorkflowRun cancelled = this.run(WorkflowRunStatus.CANCELLED, null, null, List.of(), 0);
        this.persisted.set(cancelled);

        this.useCase.execute(RUN_ID);

        verify(this.executor, never()).secureCancellation(org.mockito.ArgumentMatchers.any());
        verify(this.workflowRuns, never()).saveLifecycle(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failsClosedBeforePersistenceWhenRunningExecutionHasNoHandle() {
        final WorkflowRun run = this.run(WorkflowRunStatus.RUNNING, null, null, List.of(), 0);
        final NodeRun running = this.nodeRun(NodeRunStatus.RUNNING);
        this.persisted.set(run);
        when(this.nodeRuns.findByWorkflowRunId(RUN_ID)).thenReturn(List.of(running));
        when(this.executor.secureCancellation(running.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.useCase.execute(RUN_ID))
                .isInstanceOfSatisfying(ConflictException.class, failure ->
                        assertThat(failure.code()).isEqualTo("AGENT_EXECUTION_INTERRUPT_UNAVAILABLE"));

        verify(this.coordinator, never()).cancelActiveNodeRuns(run);
        verify(this.workflowRuns, never()).saveLifecycle(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failsClosedWhenLifecycleCancellationConflicts() {
        final WorkflowRun run = this.run(WorkflowRunStatus.RUNNING, null, null, List.of(), 0);
        final NodeRun running = this.nodeRun(NodeRunStatus.RUNNING);
        this.persisted.set(run);
        when(this.nodeRuns.findByWorkflowRunId(RUN_ID)).thenReturn(List.of(running));
        when(this.executor.secureCancellation(running.id())).thenReturn(Optional.of(() -> { }));
        when(this.coordinator.cancelActiveNodeRuns(run)).thenReturn(false);

        assertThatThrownBy(() -> this.useCase.execute(RUN_ID))
                .isInstanceOfSatisfying(ConflictException.class, failure ->
                        assertThat(failure.code()).isEqualTo("WORKFLOW_RUN_CANCELLATION_CONFLICT"));

        assertThat(this.persisted.get()).isEqualTo(run);
    }

    @ParameterizedTest
    @EnumSource(value = WorkflowRunStatus.class, names = { "SUCCEEDED", "FAILED" })
    void naturalTerminalOutcomeIsNeverRewritten(final WorkflowRunStatus status) {
        final WorkflowRun terminal = this.run(status, null, null, List.of(), 0);
        this.persisted.set(terminal);

        assertThatThrownBy(() -> this.useCase.execute(RUN_ID))
                .isInstanceOfSatisfying(ConflictException.class, failure ->
                        assertThat(failure.code()).isEqualTo("WORKFLOW_RUN_NOT_CANCELLABLE"));

        verify(this.workflowRuns, never()).saveLifecycle(org.mockito.ArgumentMatchers.any());
    }

    private WorkflowRun run(final WorkflowRunStatus status, final OperatorStopStatus operatorStopStatus,
                            final String failureCode, final List<UUID> pendingNodeRunIds, final long attempt) {
        return new WorkflowRun(RUN_ID, UUID.randomUUID(), UUID.randomUUID(), null, "Workflow", "input",
                status, List.of(), List.of(), List.of(), null, null, null, NOW.minusSeconds(10),
                status == WorkflowRunStatus.QUEUED ? null : NOW.minusSeconds(9),
                status == WorkflowRunStatus.CANCELLED ? NOW : null, List.of(), operatorStopStatus,
                failureCode, pendingNodeRunIds, attempt);
    }

    private NodeRun nodeRun(final NodeRunStatus status) {
        return new NodeRun(UUID.randomUUID(), RUN_ID, UUID.randomUUID(), UUID.randomUUID(), "Agent", "Do work",
                null, null, null, UUID.randomUUID(), null, null, null, null, status, null, null,
                new NodeRunExecutionModel("codex", "gpt-5", null), NOW.minusSeconds(8),
                status == NodeRunStatus.PENDING ? null : NOW.minusSeconds(7), null, null,
                NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE, 1);
    }
}
