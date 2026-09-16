package com.sitionix.forgeagent.domain.model;

/** Shared command/read policy. Pending work is evaluated across every turn in the session. */
public final class AgentContextResetEligibility {
    public static final String NOT_ALLOWED = "AGENT_CONTEXT_RESET_NOT_ALLOWED";
    public static final String BUSY = "AGENT_CONTEXT_RESET_BUSY";

    private AgentContextResetEligibility() { }

    public static String reason(final AgentExecutionSession session, final boolean pendingTurns) {
        if (!session.contextMode().reusable() || session.contextResetAt() != null || session.contextForkedAt() != null) {
            return NOT_ALLOWED;
        }
        if (session.status() == AgentExecutionSessionStatus.FORKING || pendingTurns || session.activeNodeRunId() != null || session.leaseOwnerId() != null
                || session.leaseExpiresAt() != null || session.status() == AgentExecutionSessionStatus.CREATING
                || session.status() == AgentExecutionSessionStatus.RESUMING || session.status() == AgentExecutionSessionStatus.ACTIVE) {
            return BUSY;
        }
        if (session.status() != AgentExecutionSessionStatus.IDLE || session.failureCode() != null
                || session.failureMessage() != null || session.terminalOutcome() != null || session.closedAt() != null) {
            return NOT_ALLOWED;
        }
        return null;
    }

    public static boolean pending(final AgentExecutionTurn turn) {
        return turn.status() == AgentExecutionTurnStatus.QUEUED || turn.status() == AgentExecutionTurnStatus.STARTING
                || turn.status() == AgentExecutionTurnStatus.ACTIVE;
    }
}
