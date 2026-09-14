package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.model.AgentExecutionRecoveryClaim;
import com.sitionix.forgeagent.domain.model.AgentExecutionRecoveryDisposition;
import com.sitionix.forgeagent.domain.model.AgentExecutionRecoveryReconciliation;
import com.sitionix.forgeagent.domain.model.NodeContextMode;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.ProviderTurnRecoveryState;
import com.sitionix.forgeagent.domain.model.ProviderTurnRecoveryTerminalOutcome;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class AgentExecutionRecoveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AgentExecutionSessionRepository sessions = mock(AgentExecutionSessionRepository.class);
    private final WorkflowRunRepository workflows = mock(WorkflowRunRepository.class);
    private final ExecutionWorkspaceResolver workspaces = mock(ExecutionWorkspaceResolver.class);
    private final AgentExecutionRecoveryInspector inspector = mock(AgentExecutionRecoveryInspector.class);
    private final UUID projectId = UUID.randomUUID();
    private final ExecutionWorkspace workspace = new ExecutionWorkspace(Path.of("/forge/project"), List.of(Path.of("/forge/project/repo")));
    private AgentExecutionRecoveryService service;
    private AgentExecutionRecoveryClaim claim;

    @BeforeEach
    void setUp() {
        this.service = new AgentExecutionRecoveryService(
                this.sessions, List.of(this.inspector), this.workflows, this.workspaces, CLOCK);
        this.claim = this.claim(NodeRunStatus.RUNNING, "codex", "0.154.0", "thread-exact", "turn-exact");
        when(this.sessions.claimExpiredRecovery(anyString())).thenAnswer(call -> Optional.of(this.claim));
        when(this.sessions.reconcileRecovery(any(), any())).thenReturn(true);
    }

    @ParameterizedTest
    @EnumSource(value = NodeRunStatus.class, names = {"SUCCEEDED", "FAILED", "CANCELLED", "BLOCKED"})
    void terminalForgeTruthSkipsEveryProviderAndWorkspaceOperation(final NodeRunStatus status) {
        this.claim = this.claim(status, null, null, null, null);
        assertThat(this.service.reconcileExpired()).isEqualTo(1);
        assertThat(this.reconciliation()).isEqualTo(new AgentExecutionRecoveryReconciliation(
                AgentExecutionRecoveryDisposition.FORGE_TERMINAL, null, "persisted-code", "persisted-message"));
        verifyNoInteractions(this.inspector, this.workspaces, this.workflows);
        verify(this.sessions).reconcileRecovery(eq(this.claim), any());
    }

    @ParameterizedTest
    @EnumSource(ProviderTurnRecoveryState.class)
    void classifiesExactProviderTurnWithoutAnExecutionPort(final ProviderTurnRecoveryState state) {
        this.inspectable();
        when(this.inspector.inspect(any())).thenReturn(switch (state) {
            case TERMINAL -> ProviderTurnRecoveryResult.terminal(ProviderTurnRecoveryTerminalOutcome.SUCCEEDED, "exact terminal turn");
            case ACTIVE -> ProviderTurnRecoveryResult.active("exact active turn");
            case UNKNOWN -> ProviderTurnRecoveryResult.unknown("unavailable");
        });
        assertThat(this.service.reconcileExpired()).isEqualTo(1);
        verify(this.inspector).inspect(new AgentExecutionRecoveryInspection(
                "codex", "0.154.0", "thread-exact", "turn-exact", this.workspace, NOW.plusSeconds(27)));
        final var result = this.reconciliation();
        switch (state) {
            case TERMINAL -> {
                assertThat(result.disposition()).isEqualTo(AgentExecutionRecoveryDisposition.PROVIDER_TERMINAL_RESULT_LOST);
                assertThat(result.providerTerminalOutcome()).isEqualTo(ProviderTurnRecoveryTerminalOutcome.SUCCEEDED);
                assertThat(result.failureCode()).isEqualTo("AGENT_EXECUTION_RECOVERY_REQUIRED");
                assertThat(result.failureMessage()).isEqualTo("The provider turn is terminal, but Forge restarted before execution result/routing was committed.");
            }
            case ACTIVE -> {
                assertThat(result.disposition()).isEqualTo(AgentExecutionRecoveryDisposition.PROVIDER_ACTIVE_FAIL_CLOSED);
                assertThat(result.failureCode()).isEqualTo("AGENT_EXECUTION_RECOVERY_PROVIDER_ACTIVE");
                assertThat(result.failureMessage()).contains("still active").doesNotContain("was stopped", "interrupted");
                assertThat(result.providerTerminalOutcome()).isNull();
            }
            case UNKNOWN -> this.assertUnknown(result);
        }
        verify(this.sessions, times(1)).claimExpiredRecovery(anyString());
        verify(this.sessions, times(1)).reconcileRecovery(eq(this.claim), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider-null", "provider-blank", "version-null", "version-blank", "thread-null", "thread-blank", "turn-null", "turn-blank"})
    void incompleteIdentityNeverInspectsOrResolvesWorkspace(final String missing) {
        this.claim = this.claim(NodeRunStatus.RUNNING,
                missing.startsWith("provider") ? this.missing(missing) : "codex",
                missing.startsWith("version") ? this.missing(missing) : "0.154.0",
                missing.startsWith("thread") ? this.missing(missing) : "thread-exact",
                missing.startsWith("turn") ? this.missing(missing) : "turn-exact");
        assertThat(this.service.reconcileExpired()).isEqualTo(1);
        this.assertUnknown(this.reconciliation());
        verifyNoInteractions(this.inspector, this.workspaces, this.workflows);
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider", "version"})
    void unsupportedProviderOrVersionFailsBeforeWorkspaceResolution(final String unsupported) {
        this.claim = this.claim(NodeRunStatus.RUNNING, unsupported.equals("provider") ? "other" : "codex",
                unsupported.equals("version") ? "0.153.2" : "0.154.0", "thread-exact", "turn-exact");
        assertThat(this.service.reconcileExpired()).isEqualTo(1);
        this.assertUnknown(this.reconciliation());
        verify(this.inspector, never()).inspect(any());
        verifyNoInteractions(this.workspaces, this.workflows);
    }

    @ParameterizedTest
    @ValueSource(strings = {"workflow-missing", "workflow-error", "workspace-missing", "workspace-error", "inspection-error", "timeout", "null-result", "supports-error"})
    void failuresStayUnknown(final String scenario) {
        when(this.inspector.supports("codex", "0.154.0")).thenReturn(true);
        if (scenario.equals("supports-error")) {
            when(this.inspector.supports(any(), any())).thenThrow(new IllegalStateException("registry error"));
        } else if (scenario.equals("workflow-error")) {
            when(this.workflows.findById(this.claim.workflowRunId())).thenThrow(new IllegalStateException("read error"));
        } else if (!scenario.equals("workflow-missing")) {
            when(this.workflows.findById(this.claim.workflowRunId())).thenReturn(Optional.of(this.workflow()));
            if (scenario.equals("workspace-error")) {
                when(this.workspaces.resolve(any(), any(), any())).thenThrow(new ExecutionWorkspaceException("checkout unavailable"));
            } else if (!scenario.equals("workspace-missing")) {
                when(this.workspaces.resolve(any(), any(), any())).thenReturn(this.workspace);
                if (scenario.equals("timeout")) when(this.inspector.inspect(any())).thenThrow(new CompletionException(new TimeoutException("timed out")));
                if (scenario.equals("inspection-error")) when(this.inspector.inspect(any())).thenThrow(new IllegalStateException("protocol error"));
            }
        }
        assertThat(this.service.reconcileExpired()).isEqualTo(1);
        this.assertUnknown(this.reconciliation());
        if (!List.of("inspection-error", "timeout", "null-result").contains(scenario)) verify(this.inspector, never()).inspect(any());
    }

    @Test
    void noCandidateAndStaleResultAreNotCountedOrRetried() {
        when(this.sessions.claimExpiredRecovery(anyString())).thenReturn(Optional.empty());
        assertThat(this.service.reconcileExpired()).isZero();
        verify(this.sessions, never()).reconcileRecovery(any(), any());
        verifyNoInteractions(this.workflows, this.workspaces, this.inspector);
        when(this.sessions.claimExpiredRecovery(anyString())).thenReturn(Optional.of(this.claim));
        when(this.sessions.reconcileRecovery(any(), any())).thenReturn(false);
        assertThat(this.service.reconcileExpired()).isZero();
        verify(this.sessions, times(2)).claimExpiredRecovery(anyString());
        verify(this.sessions, times(1)).reconcileRecovery(eq(this.claim), any());
    }

    @Test
    void providerInspectionDeadlineReservesRepositoryCommitTimeFromClaimLease() {
        this.claim = this.withLeaseExpiry(this.claim, NOW.plusSeconds(11));
        this.inspectable();
        when(this.inspector.inspect(any())).thenReturn(ProviderTurnRecoveryResult.unknown("deadline reached"));

        assertThat(this.service.reconcileExpired()).isEqualTo(1);

        final var inspection = ArgumentCaptor.forClass(AgentExecutionRecoveryInspection.class);
        verify(this.inspector).inspect(inspection.capture());
        assertThat(inspection.getValue().deadline()).isEqualTo(NOW.plusSeconds(8));
    }

    @Test
    void deadlinePersistsUnknownAndIgnoresLateInspectorResultOutsideTransaction() throws Exception {
        this.claim = this.withLeaseExpiry(this.claim, NOW.plusMillis(3_080));
        this.inspectable();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch exited = new CountDownLatch(1);
        when(this.inspector.inspect(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();
            awaitUninterruptibly(release);
            exited.countDown();
            return ProviderTurnRecoveryResult.terminal(
                    ProviderTurnRecoveryTerminalOutcome.SUCCEEDED, "late terminal result");
        });
        final CompletableFuture<Integer> reconciliation = CompletableFuture.supplyAsync(this.service::reconcileExpired);

        try {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(reconciliation.get(400, TimeUnit.MILLISECONDS)).isEqualTo(1);
            this.assertUnknown(this.reconciliation());
            verify(this.sessions, times(1)).reconcileRecovery(eq(this.claim), any());

            release.countDown();
            assertThat(exited.await(1, TimeUnit.SECONDS)).isTrue();
            verify(this.sessions, times(1)).reconcileRecovery(eq(this.claim), any());
        } finally {
            release.countDown();
            reconciliation.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void workerIdentityIsStablePerInstanceAndDistinctAcrossInstances() {
        when(this.sessions.claimExpiredRecovery(anyString())).thenReturn(Optional.empty());
        this.service.reconcileExpired();
        this.service.reconcileExpired();
        new AgentExecutionRecoveryService(this.sessions, List.of(), this.workflows, this.workspaces, CLOCK)
                .reconcileExpired();
        final var owners = ArgumentCaptor.forClass(String.class);
        verify(this.sessions, times(3)).claimExpiredRecovery(owners.capture());
        assertThat(owners.getAllValues().get(0)).isNotBlank().isEqualTo(owners.getAllValues().get(1)).isNotEqualTo(owners.getAllValues().get(2));
    }

    @Test
    void inspectionRunsAfterClaimTransactionClosesAndSuspendsAnAmbientTransaction() {
        this.inspectable();
        final var manager = new TestTransactionManager();
        final var transaction = new TransactionTemplate(manager);
        when(this.sessions.claimExpiredRecovery(anyString())).thenAnswer(call -> transaction.execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return Optional.of(this.claim);
        }));
        when(this.inspector.inspect(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return ProviderTurnRecoveryResult.active("exact active turn");
        });
        when(this.sessions.reconcileRecovery(any(), any())).thenAnswer(call -> transaction.execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return true;
        }));
        final var proxy = new ProxyFactory(this.service);
        final var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(interceptor);
        final var proxied = (AgentExecutionRecoveryService) proxy.getProxy();
        transaction.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(proxied.reconcileExpired()).isEqualTo(1);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        });
        verify(this.inspector).inspect(any());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private void inspectable() {
        when(this.inspector.supports("codex", "0.154.0")).thenReturn(true);
        when(this.workflows.findById(this.claim.workflowRunId())).thenReturn(Optional.of(this.workflow()));
        when(this.workspaces.resolve(this.projectId, this.claim.repositoryId(), List.of(this.claim.repositoryId()))).thenReturn(this.workspace);
    }

    private WorkflowRun workflow() {
        return new WorkflowRun(this.claim.workflowRunId(), this.projectId, UUID.randomUUID(), UUID.randomUUID(),
                "workflow", "input", WorkflowRunStatus.RUNNING, List.of(), List.of(), List.of(), null, null, null,
                Instant.now(), Instant.now(), null, List.of(this.claim.repositoryId()));
    }

    private AgentExecutionRecoveryClaim claim(final NodeRunStatus status, final String provider, final String version,
                                             final String thread, final String turn) {
        return new AgentExecutionRecoveryClaim(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), provider, version, thread, turn, NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE,
                status, "persisted-code", "persisted-message", "recovery-owner", 7, NOW.plusSeconds(30));
    }

    private AgentExecutionRecoveryClaim withLeaseExpiry(final AgentExecutionRecoveryClaim value,
                                                        final Instant leaseExpiresAt) {
        return new AgentExecutionRecoveryClaim(
                value.sessionId(), value.turnId(), value.nodeRunId(), value.workflowRunId(), value.repositoryId(),
                value.providerId(), value.providerVersion(), value.providerConversationId(), value.providerTurnId(),
                value.contextMode(), value.nodeRunStatus(), value.failureCode(), value.failureMessage(),
                value.ownerId(), value.leaseToken(), leaseExpiresAt);
    }

    private String missing(final String scenario) { return scenario.endsWith("null") ? null : " "; }

    private AgentExecutionRecoveryReconciliation reconciliation() {
        final var result = ArgumentCaptor.forClass(AgentExecutionRecoveryReconciliation.class);
        verify(this.sessions).reconcileRecovery(eq(this.claim), result.capture());
        return result.getValue();
    }

    private void assertUnknown(final AgentExecutionRecoveryReconciliation result) {
        assertThat(result.disposition()).isEqualTo(AgentExecutionRecoveryDisposition.PROVIDER_UNKNOWN_FAIL_CLOSED);
        assertThat(result.failureCode()).isEqualTo("AGENT_EXECUTION_RECOVERY_UNKNOWN");
        assertThat(result.failureMessage()).isNotBlank();
        assertThat(result.providerTerminalOutcome()).isNull();
    }

    private static void awaitUninterruptibly(final CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (final InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        private final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);
        @Override protected Object doGetTransaction() { return this.active.get(); }
        @Override protected boolean isExistingTransaction(final Object transaction) { return (Boolean) transaction; }
        @Override protected void doBegin(final Object transaction, final TransactionDefinition definition) { this.active.set(true); }
        @Override protected void doCommit(final DefaultTransactionStatus status) { }
        @Override protected void doRollback(final DefaultTransactionStatus status) { }
        @Override protected Object doSuspend(final Object transaction) { this.active.set(false); return true; }
        @Override protected void doResume(final Object transaction, final Object suspendedResources) { this.active.set(true); }
        @Override protected void doCleanupAfterCompletion(final Object transaction) { this.active.remove(); }
    }
}
