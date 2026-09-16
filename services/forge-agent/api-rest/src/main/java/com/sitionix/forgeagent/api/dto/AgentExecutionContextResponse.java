package com.sitionix.forgeagent.api.dto;

import java.time.Instant;
import java.util.UUID;

public record AgentExecutionContextResponse(
        UUID sessionId, UUID turnId, UUID nodeRunId, UUID sourceNodeId, UUID repositoryId,
        String contextMode, Integer sequence, String sessionStatus, String turnStatus,
        String provider, String providerConversationId, String providerTurnId, String providerVersion,
        String failureCode, String failureMessage, Instant createdAt, Instant startedAt, Instant finishedAt,
        Instant contextResetAt, boolean resetAllowed, String resetReason,
        UUID contextIterationId, String contextGroupKey,
        Instant contextForkedAt, UUID forkedFromSessionId, UUID forkedFromTurnId, boolean forkAllowed, String forkReason) {

    public AgentExecutionContextResponse(UUID sessionId, UUID turnId, UUID nodeRunId, UUID sourceNodeId, UUID repositoryId,
        String contextMode, Integer sequence, String sessionStatus, String turnStatus,
        String provider, String providerConversationId, String providerTurnId, String providerVersion,
        String failureCode, String failureMessage, Instant createdAt, Instant startedAt, Instant finishedAt,
        Instant contextResetAt, boolean resetAllowed, String resetReason, UUID contextIterationId, String contextGroupKey) {
        this(sessionId, turnId, nodeRunId, sourceNodeId, repositoryId, contextMode, sequence, sessionStatus, turnStatus,
                provider, providerConversationId, providerTurnId, providerVersion, failureCode, failureMessage,
                createdAt, startedAt, finishedAt, contextResetAt, resetAllowed, resetReason, contextIterationId, contextGroupKey,
                null, null, null, false, "AGENT_CONTEXT_FORK_NOT_ALLOWED");
    }

    public AgentExecutionContextResponse(UUID sessionId, UUID turnId, UUID nodeRunId, UUID sourceNodeId, UUID repositoryId,
        String contextMode, Integer sequence, String sessionStatus, String turnStatus,
        String provider, String providerConversationId, String providerTurnId, String providerVersion,
        String failureCode, String failureMessage, Instant createdAt, Instant startedAt, Instant finishedAt,
        Instant contextResetAt, boolean resetAllowed, String resetReason) {
        this(sessionId, turnId, nodeRunId, sourceNodeId, repositoryId, contextMode, sequence, sessionStatus, turnStatus,
                provider, providerConversationId, providerTurnId, providerVersion, failureCode, failureMessage,
                createdAt, startedAt, finishedAt, contextResetAt, resetAllowed, resetReason, null, null);
    }
}
