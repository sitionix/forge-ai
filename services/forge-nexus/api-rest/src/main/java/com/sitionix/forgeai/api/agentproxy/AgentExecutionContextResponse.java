package com.sitionix.forgeai.api.agentproxy;

import java.time.Instant;
import java.util.UUID;

public record AgentExecutionContextResponse(UUID sessionId, UUID turnId, UUID nodeRunId, UUID sourceNodeId,
        UUID repositoryId, String contextMode, int sequence, String sessionStatus, String turnStatus,
        String provider, String providerConversationId, String providerTurnId, String providerVersion,
        String failureCode, String failureMessage, Instant createdAt, Instant startedAt, Instant finishedAt) {
}
