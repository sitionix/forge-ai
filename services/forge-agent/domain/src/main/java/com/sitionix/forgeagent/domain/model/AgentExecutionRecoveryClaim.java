package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Immutable persisted execution snapshot and turn-scoped recovery fence. */
public record AgentExecutionRecoveryClaim(
        UUID sessionId, UUID turnId, UUID nodeRunId, UUID workflowRunId, UUID repositoryId,
        String providerId, String providerVersion, String providerConversationId, String providerTurnId,
        NodeContextMode contextMode, NodeRunStatus nodeRunStatus, String failureCode, String failureMessage,
        String ownerId, long leaseToken, Instant leaseExpiresAt) {
}
