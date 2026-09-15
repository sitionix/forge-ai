package com.sitionix.forgeagent.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.AgentExecutionAllocation;
import com.sitionix.forgeagent.domain.model.AgentExecutionSession;
import com.sitionix.forgeagent.domain.model.AgentExecutionSessionStatus;
import com.sitionix.forgeagent.domain.model.AgentExecutionTurn;
import com.sitionix.forgeagent.domain.model.AgentExecutionTurnStatus;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.NodeContextMode;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.NodeRunFailure;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.ProviderTurnRecoveryState;
import com.sitionix.forgeagent.domain.model.ProviderTurnRecoveryTerminalOutcome;
import com.sitionix.forgeagent.domain.model.RecoveredNodeRunRetryAction;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RetryRecoveredNodeRunUseCaseTest {

    private static final UUID RUN_ID = UUID.fromString("51000000-0000-4000-8000-000000000001");
    private static final UUID NODE_ID = UUID.fromString("51000000-0000-4000-8000-000000000002");
    private static final UUID SESSION_ID = UUID.fromString("51000000-0000-4000-8000-000000000003");
    private static final Instant NOW = Instant.parse("2026-09-15T08:00:00Z");

    private final WorkflowRunRepository workflows = mock(WorkflowRunRepository.class);
    private final NodeRunRepository nodeRuns = mock(NodeRunRepository.class);
    private final AgentExecutionSessionRepository sessions = mock(AgentExecutionSessionRepository.class);
    private final AtomicReference<WorkflowRun> persistedRun = new AtomicReference<>();
    private final AtomicReference<NodeRun> persistedChild = new AtomicReference<>();
    private final RecoveredNodeRunRetryEligibilityService eligibility =
            new RecoveredNodeRunRetryEligibilityService(this.sessions);
    private final RetryRecoveredNodeRunUseCase useCase = new RetryRecoveredNodeRunUseCase(
            this.workflows, this.nodeRuns, this.eligibility, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void configurePersistence() {
        when(this.workflows.findByIdForUpdate(RUN_ID)).thenAnswer(ignored -> Optional.ofNullable(this.persistedRun.get()));
        when(this.workflows.reopenForRetry(any())).thenAnswer(invocation -> {
            final WorkflowRun run = invocation.getArgument(0);
            this.persistedRun.set(run);
            return run;
        });
        when(this.nodeRuns.findByIdForUpdate(NODE_ID)).thenAnswer(ignored -> Optional.ofNullable(
                this.persistedRun.get().nodeRuns().stream().filter(node -> node.id().equals(NODE_ID)).findFirst().orElse(null)));
        when(this.nodeRuns.findRetryChild(NODE_ID)).thenAnswer(ignored -> Optional.ofNullable(this.persistedChild.get()));
        when(this.nodeRuns.saveAndFlush(any())).thenAnswer(invocation -> {
            final NodeRun child = invocation.getArgument(0);
            this.persistedChild.set(child);
            return child;
        });
    }

    @Test
    void freshRetryCreatesCleanAttemptFromSnapshotAndReopensWorkflow() {
        final NodeRun failed = recovered(NodeContextMode.FRESH_EACH_NODE_RUN);
        this.persistedRun.set(run(WorkflowRunStatus.FAILED, List.of(failed), new NodeRunOutput("{\"old\":true}")));
        when(this.nodeRuns.findByWorkflowRunId(RUN_ID)).thenReturn(List.of(failed));
        when(this.sessions.findByNodeRunId(NODE_ID)).thenReturn(Optional.of(allocation(
                failed, ProviderTurnRecoveryState.TERMINAL, AgentExecutionSessionStatus.CLOSED)));

        final RetryRecoveredNodeRunResult result = this.useCase.execute(RUN_ID, NODE_ID);

        final NodeRun child = this.persistedChild.get();
        assertThat(result.nodeRunId()).isEqualTo(child.id()).isNotEqualTo(failed.id());
        assertThat(child.retryOfNodeRunId()).isEqualTo(failed.id());
        assertThat(child.status()).isEqualTo(NodeRunStatus.PENDING);
        assertThat(child).usingRecursiveComparison()
                .ignoringFields("id", "retryOfNodeRunId", "status", "output", "failure", "selectedOutputPortId",
                        "routingCompletedAt", "createdAt", "startedAt", "finishedAt")
                .isEqualTo(failed);
        assertThat(child.createdAt()).isEqualTo(NOW);
        assertThat(child.output()).isNull();
        assertThat(child.failure()).isNull();
        assertThat(child.startedAt()).isNull();
        assertThat(child.finishedAt()).isNull();
        assertThat(this.persistedRun.get().status()).isEqualTo(WorkflowRunStatus.RUNNING);
        assertThat(this.persistedRun.get().finishedAt()).isNull();
        assertThat(this.persistedRun.get().result()).isNull();
        assertThat(this.persistedRun.get().resultSourceNodeRunId()).isNull();
    }

    @Test
    void reusableTerminalRecoveryIsDescribedAsResume() {
        final NodeRun failed = recovered(NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE);
        final WorkflowRun run = run(WorkflowRunStatus.FAILED, List.of(failed), null);
        when(this.sessions.findByNodeRunId(NODE_ID)).thenReturn(Optional.of(allocation(
                failed, ProviderTurnRecoveryState.TERMINAL, AgentExecutionSessionStatus.IDLE)));

        assertThat(this.eligibility.evaluate(run, failed, List.of(failed)).action())
                .isEqualTo(RecoveredNodeRunRetryAction.RESUME);
    }

    @ParameterizedTest
    @EnumSource(value = ProviderTurnRecoveryState.class, names = {"ACTIVE", "UNKNOWN"})
    void activeAndUnknownRecoveryCannotCreateWork(final ProviderTurnRecoveryState recoveryState) {
        final NodeRun failed = recovered(NodeContextMode.FRESH_EACH_NODE_RUN);
        final WorkflowRun original = run(WorkflowRunStatus.FAILED, List.of(failed), null);
        this.persistedRun.set(original);
        when(this.nodeRuns.findByWorkflowRunId(RUN_ID)).thenReturn(List.of(failed));
        when(this.sessions.findByNodeRunId(NODE_ID)).thenReturn(Optional.of(allocation(
                failed, recoveryState, AgentExecutionSessionStatus.CLOSED)));

        assertThatThrownBy(() -> this.useCase.execute(RUN_ID, NODE_ID))
                .isInstanceOfSatisfying(ConflictException.class, error ->
                        assertThat(error.code()).isEqualTo("WORKFLOW_RUN_RETRY_NOT_ALLOWED"));

        assertThat(this.persistedRun.get()).isSameAs(original);
        verify(this.nodeRuns, never()).saveAndFlush(any());
    }

    @Test
    void anotherCancelledCurrentLeafMakesContinuationUnsafe() {
        final NodeRun failed = recovered(NodeContextMode.FRESH_EACH_NODE_RUN);
        final NodeRun cancelled = node(UUID.randomUUID(), NodeRunStatus.CANCELLED, null, null);
        final WorkflowRun original = run(WorkflowRunStatus.FAILED, List.of(failed, cancelled), null);
        this.persistedRun.set(original);
        when(this.nodeRuns.findByWorkflowRunId(RUN_ID)).thenReturn(List.of(failed, cancelled));
        when(this.sessions.findByNodeRunId(NODE_ID)).thenReturn(Optional.of(allocation(
                failed, ProviderTurnRecoveryState.TERMINAL, AgentExecutionSessionStatus.CLOSED)));

        assertThatThrownBy(() -> this.useCase.execute(RUN_ID, NODE_ID))
                .isInstanceOfSatisfying(ConflictException.class, error ->
                        assertThat(error.code()).isEqualTo("WORKFLOW_RUN_RETRY_UNSAFE"));

        assertThat(this.persistedRun.get()).isSameAs(original);
        verify(this.nodeRuns, never()).saveAndFlush(any());
    }

    @Test
    void duplicateRequestReturnsExistingDirectChildWithoutReopeningAgain() {
        final NodeRun failed = recovered(NodeContextMode.FRESH_EACH_NODE_RUN);
        final NodeRun child = node(UUID.randomUUID(), NodeRunStatus.PENDING, NODE_ID, null);
        this.persistedRun.set(run(WorkflowRunStatus.RUNNING, List.of(failed, child), null));
        this.persistedChild.set(child);

        final RetryRecoveredNodeRunResult result = this.useCase.execute(RUN_ID, NODE_ID);

        assertThat(result.nodeRunId()).isEqualTo(child.id());
        verify(this.nodeRuns, never()).saveAndFlush(any());
        verify(this.workflows, never()).reopenForRetry(any());
    }

    @ParameterizedTest
    @EnumSource(value = WorkflowRunStatus.class, names = {"CANCELLED", "SUCCEEDED"})
    void terminalWorkflowCannotBeReopened(final WorkflowRunStatus status) {
        final NodeRun failed = recovered(NodeContextMode.FRESH_EACH_NODE_RUN);
        this.persistedRun.set(run(status, List.of(failed), null));

        assertThatThrownBy(() -> this.useCase.execute(RUN_ID, NODE_ID))
                .isInstanceOfSatisfying(ConflictException.class, error ->
                        assertThat(error.code()).isEqualTo("WORKFLOW_RUN_RETRY_NOT_ALLOWED"));

        verify(this.nodeRuns, never()).saveAndFlush(any());
    }

    private NodeRun recovered(final NodeContextMode contextMode) {
        return new NodeRun(NODE_ID, RUN_ID, UUID.randomUUID(), UUID.randomUUID(), "Snapshot agent", "Immutable instructions",
                AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"), NodeInputMode.TASK_AND_DEPENDENCIES,
                new NodePosition(12.0, 24.0), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null,
                NodeRunStatus.FAILED, null, new NodeRunFailure("AGENT_EXECUTION_RECOVERY_REQUIRED", "lost"),
                new NodeRunExecutionModel("codex", "gpt-5", "high"), NOW.minusSeconds(20), NOW.minusSeconds(19),
                NOW.minusSeconds(10), UUID.randomUUID(), contextMode, 1, null);
    }

    private NodeRun node(final UUID id, final NodeRunStatus status, final UUID retryOf, final NodeRunFailure failure) {
        return new NodeRun(id, RUN_ID, UUID.randomUUID(), UUID.randomUUID(), "Agent", "Instructions",
                AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"), NodeInputMode.DEPENDENCIES_ONLY,
                new NodePosition(0, 0), UUID.randomUUID(), null, null, null, null, status, null, failure,
                new NodeRunExecutionModel("codex", "gpt-5", null), NOW.minusSeconds(5), null, null, null,
                NodeContextMode.FRESH_EACH_NODE_RUN, 1, retryOf);
    }

    private WorkflowRun run(final WorkflowRunStatus status, final List<NodeRun> nodes, final NodeRunOutput result) {
        return new WorkflowRun(RUN_ID, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Workflow", "input",
                status, nodes, List.of(), List.of(), null, result, result == null ? null : UUID.randomUUID(),
                NOW.minusSeconds(30), NOW.minusSeconds(29), status == WorkflowRunStatus.RUNNING ? null : NOW,
                List.of(), null, null, List.of(), 0);
    }

    private AgentExecutionAllocation allocation(final NodeRun node, final ProviderTurnRecoveryState recoveryState,
                                                final AgentExecutionSessionStatus sessionStatus) {
        final AgentExecutionSession session = new AgentExecutionSession(SESSION_ID, RUN_ID, node.sourceNodeId(),
                node.sourceAgentId(), node.repositoryId(), "codex", "thread-1", "0.154.0", node.contextMode(),
                sessionStatus, null, null, null, 4, null, null, null, NOW.minusSeconds(25), NOW.minusSeconds(9),
                sessionStatus == AgentExecutionSessionStatus.CLOSED ? NOW.minusSeconds(9) : null);
        final AgentExecutionTurn turn = new AgentExecutionTurn(UUID.randomUUID(), SESSION_ID, node.id(), "turn-1", 1,
                AgentExecutionTurnStatus.FAILED, "AGENT_EXECUTION_RECOVERY_REQUIRED", "lost", recoveryState,
                ProviderTurnRecoveryTerminalOutcome.SUCCEEDED, NOW.minusSeconds(10), NOW.minusSeconds(19),
                NOW.minusSeconds(10), NOW.minusSeconds(20), NOW.minusSeconds(10));
        return new AgentExecutionAllocation(session, turn);
    }
}
