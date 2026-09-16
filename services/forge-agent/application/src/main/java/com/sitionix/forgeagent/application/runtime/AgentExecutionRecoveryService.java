package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.AgentExecutionRecoveryClaim;
import com.sitionix.forgeagent.domain.model.AgentExecutionRecoveryDisposition;
import com.sitionix.forgeagent.domain.model.AgentExecutionRecoveryReconciliation;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Bounded recovery orchestration. Only the repository's claim and commit calls hold DB transactions. */
@Service
@RequiredArgsConstructor
public class AgentExecutionRecoveryService {
    static final Duration RECOVERY_COMMIT_RESERVE = Duration.ofSeconds(3);
    static final Duration MAX_INSPECTION_BUDGET = Duration.ofSeconds(27);

    private final AgentExecutionSessionRepository sessions;
    private final List<AgentExecutionRecoveryInspector> inspectors;
    private final WorkflowRunRepository workflows;
    private final ExecutionWorkspaceResolver workspaces;
    private final NodeRunRepository nodeRuns;
    private final NodeRunCompletionProcessor completionProcessor;
    private final WorkflowExecutionCoordinator coordinator;
    private final Clock clock;
    private final String ownerId = "agent-recovery-" + UUID.randomUUID();

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int reconcileExpired() {
        final int recoveredForks = this.sessions.reconcileStaleForks();
        final var candidate = this.sessions.claimExpiredRecovery(this.ownerId);
        if (candidate.isEmpty()) return recoveredForks;
        final AgentExecutionRecoveryClaim claim = candidate.get();
        final AgentExecutionRecoveryReconciliation reconciliation;
        if (claim.nodeRunStatus() == NodeRunStatus.SUCCEEDED || claim.nodeRunStatus() == NodeRunStatus.FAILED
                || claim.nodeRunStatus() == NodeRunStatus.CANCELLED || claim.nodeRunStatus() == NodeRunStatus.BLOCKED) {
            reconciliation = new AgentExecutionRecoveryReconciliation(AgentExecutionRecoveryDisposition.FORGE_TERMINAL,
                    null, claim.failureCode(), claim.failureMessage());
        } else {
            reconciliation = this.reconciliation(this.inspect(claim));
        }
        if (!this.sessions.reconcileRecovery(claim, reconciliation)) return 0;
        this.continueNodeRunLifecycle(claim.nodeRunId());
        return 1;
    }

    private void continueNodeRunLifecycle(final UUID nodeRunId) {
        this.nodeRuns.findById(nodeRunId).ifPresent(nodeRun -> {
            switch (nodeRun.status()) {
                case SUCCEEDED -> {
                    if (nodeRun.routingCompletedAt() == null) this.completionProcessor.process(nodeRun.id());
                }
                case FAILED, BLOCKED -> this.coordinator.reconcile(nodeRun.workflowRunId());
                case PENDING, RUNNING, CANCELLED -> {
                    // Recovery does not restart active work or recreate cancelled downstream work.
                }
            }
        });
    }

    private ProviderTurnRecoveryResult inspect(final AgentExecutionRecoveryClaim claim) {
        if (blank(claim.providerId()) || blank(claim.providerVersion())
                || blank(claim.providerConversationId()) || blank(claim.providerTurnId())) {
            return ProviderTurnRecoveryResult.unknown("Persisted provider identity is incomplete.");
        }
        try {
            final var supported = this.inspectors.stream()
                    .filter(inspector -> inspector.supports(claim.providerId(), claim.providerVersion())).toList();
            if (supported.size() != 1) {
                return ProviderTurnRecoveryResult.unknown("No unambiguous recovery inspector supports the persisted provider and version.");
            }
            final var workflow = this.workflows.findById(claim.workflowRunId());
            if (workflow.isEmpty()) return ProviderTurnRecoveryResult.unknown("Owning workflow run is unavailable.");
            final var run = workflow.get();
            final var workspace = this.workspaces.resolve(run.projectId(), claim.repositoryId(), run.repositoryIds());
            if (workspace == null) return ProviderTurnRecoveryResult.unknown("Execution workspace is unavailable.");
            final Instant now = this.clock.instant();
            final Instant leaseDeadline = claim.leaseExpiresAt().minus(RECOVERY_COMMIT_RESERVE);
            final Instant localDeadline = now.plus(MAX_INSPECTION_BUDGET);
            final Instant deadline = leaseDeadline.isBefore(localDeadline) ? leaseDeadline : localDeadline;
            if (!deadline.isAfter(now)) {
                return ProviderTurnRecoveryResult.unknown("Recovery lease does not permit provider inspection.");
            }
            final var inspection = new AgentExecutionRecoveryInspection(
                    claim.providerId(), claim.providerVersion(), claim.providerConversationId(), claim.providerTurnId(),
                    workspace, deadline);
            final var result = this.inspectWithinDeadline(supported.getFirst(), inspection);
            return result == null ? ProviderTurnRecoveryResult.unknown("Provider inspection returned no evidence.") : result;
        } catch (final RuntimeException exception) {
            // Provider diagnostics may contain transport/workspace details: persist a bounded, neutral explanation.
            return ProviderTurnRecoveryResult.unknown("Provider inspection or execution workspace resolution failed.");
        }
    }

    private ProviderTurnRecoveryResult inspectWithinDeadline(final AgentExecutionRecoveryInspector inspector,
                                                              final AgentExecutionRecoveryInspection inspection) {
        final CompletableFuture<ProviderTurnRecoveryResult> result = new CompletableFuture<>();
        final Thread worker = Thread.ofVirtual()
                .name("forge-agent-recovery-inspection-" + this.ownerId)
                .start(() -> {
                    try {
                        result.complete(inspector.inspect(inspection));
                    } catch (final RuntimeException exception) {
                        result.completeExceptionally(exception);
                    }
                });
        try {
            final Duration remaining = Duration.between(this.clock.instant(), inspection.deadline());
            if (remaining.isZero() || remaining.isNegative()) {
                worker.interrupt();
                return ProviderTurnRecoveryResult.unknown("Provider inspection exceeded the recovery deadline.");
            }
            return result.get(remaining.toNanos(), TimeUnit.NANOSECONDS);
        } catch (final TimeoutException exception) {
            worker.interrupt();
            return ProviderTurnRecoveryResult.unknown("Provider inspection exceeded the recovery deadline.");
        } catch (final InterruptedException exception) {
            worker.interrupt();
            Thread.currentThread().interrupt();
            return ProviderTurnRecoveryResult.unknown("Provider inspection was interrupted before the recovery deadline.");
        } catch (final ExecutionException exception) {
            return ProviderTurnRecoveryResult.unknown("Provider inspection failed before the recovery deadline.");
        }
    }

    private AgentExecutionRecoveryReconciliation reconciliation(final ProviderTurnRecoveryResult result) {
        return switch (result.state()) {
            case TERMINAL -> new AgentExecutionRecoveryReconciliation(
                    AgentExecutionRecoveryDisposition.PROVIDER_TERMINAL_RESULT_LOST, result.terminalOutcome(),
                    "AGENT_EXECUTION_RECOVERY_REQUIRED",
                    "The provider turn is terminal, but Forge restarted before execution result/routing was committed.");
            case ACTIVE -> new AgentExecutionRecoveryReconciliation(
                    AgentExecutionRecoveryDisposition.PROVIDER_ACTIVE_FAIL_CLOSED, null,
                    "AGENT_EXECUTION_RECOVERY_PROVIDER_ACTIVE",
                    "The exact provider turn is still active. Forge recovery cannot establish safe termination; manual recovery is required.");
            case UNKNOWN -> new AgentExecutionRecoveryReconciliation(
                    AgentExecutionRecoveryDisposition.PROVIDER_UNKNOWN_FAIL_CLOSED, null,
                    "AGENT_EXECUTION_RECOVERY_UNKNOWN",
                    "Forge could not establish the exact provider turn state after ownership expired. Manual recovery is required.");
        };
    }

    private static boolean blank(final String value) { return value == null || value.isBlank(); }
}
