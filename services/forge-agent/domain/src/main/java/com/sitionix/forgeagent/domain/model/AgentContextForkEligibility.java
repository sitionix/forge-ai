package com.sitionix.forgeagent.domain.model;

/** State-only policy shared by commands and context inspection. Provider support is checked separately. */
public final class AgentContextForkEligibility {
    public static final String NOT_ALLOWED = "AGENT_CONTEXT_FORK_NOT_ALLOWED";
    public static final String BUSY = "AGENT_CONTEXT_FORK_BUSY";
    public static final String UNSUPPORTED = "AGENT_CONTEXT_FORK_UNSUPPORTED";
    public static final String FAILED = "AGENT_CONTEXT_FORK_FAILED";
    public static final String CONFLICT = "AGENT_CONTEXT_FORK_CONFLICT";
    private AgentContextForkEligibility() { }

    public static String reason(AgentExecutionSession session, AgentExecutionTurn latest, boolean pending, boolean workflowTerminal) {
        if (workflowTerminal || !session.contextMode().reusable() || session.contextResetAt() != null || session.contextForkedAt() != null) return NOT_ALLOWED;
        if (pending || session.status() == AgentExecutionSessionStatus.FORKING || session.activeNodeRunId() != null
                || session.leaseOwnerId() != null || session.leaseExpiresAt() != null
                || session.status() == AgentExecutionSessionStatus.CREATING || session.status() == AgentExecutionSessionStatus.RESUMING
                || session.status() == AgentExecutionSessionStatus.ACTIVE) return BUSY;
        if (session.status() != AgentExecutionSessionStatus.IDLE || session.failureCode() != null || session.failureMessage() != null
                || session.terminalOutcome() != null || session.closedAt() != null || blank(session.providerConversationId())
                || blank(session.providerVersion()) || latest == null || latest.status() != AgentExecutionTurnStatus.SUCCEEDED
                || blank(latest.providerTurnId())) return NOT_ALLOWED;
        return null;
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
