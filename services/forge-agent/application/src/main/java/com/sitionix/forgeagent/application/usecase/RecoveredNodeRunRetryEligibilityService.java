package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.model.AgentExecutionAllocation;
import com.sitionix.forgeagent.domain.model.AgentExecutionSession;
import com.sitionix.forgeagent.domain.model.AgentExecutionSessionStatus;
import com.sitionix.forgeagent.domain.model.AgentExecutionTurn;
import com.sitionix.forgeagent.domain.model.AgentExecutionTurnStatus;
import com.sitionix.forgeagent.domain.model.NodeContextMode;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.ProviderTurnRecoveryState;
import com.sitionix.forgeagent.domain.model.RecoveredNodeRunRetryEligibility;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import com.sitionix.forgeagent.application.runtime.NodeRunRetryLineage;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RecoveredNodeRunRetryEligibilityService {

    public static final String NOT_ALLOWED = "WORKFLOW_RUN_RETRY_NOT_ALLOWED";
    public static final String SUPERSEDED = "NODE_RUN_RETRY_SUPERSEDED";
    public static final String UNSAFE = "WORKFLOW_RUN_RETRY_UNSAFE";
    public static final String CONTEXT_UNSAFE = "AGENT_CONTEXT_NOT_SAFELY_REUSABLE";
    private static final String RECOVERY_REQUIRED = "AGENT_EXECUTION_RECOVERY_REQUIRED";

    private final AgentExecutionSessionRepository sessions;

    public RecoveredNodeRunRetryEligibility evaluate(final WorkflowRun workflowRun, final NodeRun target,
                                                      final List<NodeRun> nodeRuns) {
        if (!workflowRun.id().equals(target.workflowRunId())
                || workflowRun.status() != WorkflowRunStatus.FAILED
                || target.status() != NodeRunStatus.FAILED
                || target.failure() == null
                || !RECOVERY_REQUIRED.equals(target.failure().code())
                || target.output() != null
                || target.routingCompletedAt() != null
                || target.contextTrackingVersion() == null) {
            return RecoveredNodeRunRetryEligibility.none(NOT_ALLOWED);
        }

        final List<NodeRun> currentLeaves = NodeRunRetryLineage.currentLeaves(nodeRuns);
        if (currentLeaves.stream().noneMatch(node -> node.id().equals(target.id()))) {
            return RecoveredNodeRunRetryEligibility.none(SUPERSEDED);
        }
        if (currentLeaves.stream().anyMatch(node -> !node.id().equals(target.id())
                && node.status() == NodeRunStatus.CANCELLED)) {
            return RecoveredNodeRunRetryEligibility.none(UNSAFE);
        }

        final AgentExecutionAllocation allocation = this.sessions.findByNodeRunId(target.id()).orElse(null);
        if (allocation == null || !this.exactTerminalRecovery(target, allocation)) {
            return RecoveredNodeRunRetryEligibility.none(NOT_ALLOWED);
        }
        if (!this.safeSessionState(target.contextMode(), allocation.session())) {
            return RecoveredNodeRunRetryEligibility.none(
                    target.contextMode() == NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE ? CONTEXT_UNSAFE : NOT_ALLOWED);
        }
        return target.contextMode() == NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE
                ? RecoveredNodeRunRetryEligibility.resume()
                : RecoveredNodeRunRetryEligibility.retry();
    }

    private boolean exactTerminalRecovery(final NodeRun target, final AgentExecutionAllocation allocation) {
        final AgentExecutionSession session = allocation.session();
        final AgentExecutionTurn turn = allocation.turn();
        return target.id().equals(turn.nodeRunId())
                && session.id().equals(turn.agentSessionId())
                && target.workflowRunId().equals(session.workflowRunId())
                && target.sourceNodeId().equals(session.sourceNodeId())
                && target.sourceAgentId().equals(session.sourceAgentId())
                && Objects.equals(target.repositoryId(), session.repositoryId())
                && target.contextMode() == session.contextMode()
                && turn.status() == AgentExecutionTurnStatus.FAILED
                && RECOVERY_REQUIRED.equals(turn.failureCode())
                && turn.providerRecoveryState() == ProviderTurnRecoveryState.TERMINAL
                && turn.providerRecoveryCheckedAt() != null
                && !blank(session.providerId())
                && !blank(session.providerConversationId())
                && !blank(session.providerVersion())
                && !blank(turn.providerTurnId());
    }

    private boolean safeSessionState(final NodeContextMode contextMode, final AgentExecutionSession session) {
        final AgentExecutionSessionStatus expected = contextMode == NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE
                ? AgentExecutionSessionStatus.IDLE : AgentExecutionSessionStatus.CLOSED;
        return session.status() == expected
                && session.activeNodeRunId() == null
                && session.leaseOwnerId() == null
                && session.leaseExpiresAt() == null;
    }

    private static boolean blank(final String value) {
        return value == null || value.isBlank();
    }
}
