package com.sitionix.forgeai.domain.model.agentproxy;

import java.time.Instant;
import java.util.UUID;

public record AgentExecutionContext(UUID sessionId, UUID turnId, UUID nodeRunId, UUID sourceNodeId,
        UUID repositoryId, String contextMode, int sequence, String sessionStatus, String turnStatus,
        String provider, String providerConversationId, String providerTurnId, String providerVersion,
        String failureCode, String failureMessage, Instant createdAt, Instant startedAt, Instant finishedAt, Instant contextResetAt, boolean resetAllowed, String resetReason,
        UUID contextIterationId) {

    public AgentExecutionContext(UUID sessionId, UUID turnId, UUID nodeRunId, UUID sourceNodeId,
        UUID repositoryId, String contextMode, int sequence, String sessionStatus, String turnStatus,
        String provider, String providerConversationId, String providerTurnId, String providerVersion,
        String failureCode, String failureMessage, Instant createdAt, Instant startedAt, Instant finishedAt, Instant contextResetAt, boolean resetAllowed, String resetReason) {
        this(sessionId, turnId, nodeRunId, sourceNodeId, repositoryId, contextMode, sequence, sessionStatus, turnStatus,
                provider, providerConversationId, providerTurnId, providerVersion, failureCode, failureMessage,
                createdAt, startedAt, finishedAt, contextResetAt, resetAllowed, resetReason, null);
    }
}
