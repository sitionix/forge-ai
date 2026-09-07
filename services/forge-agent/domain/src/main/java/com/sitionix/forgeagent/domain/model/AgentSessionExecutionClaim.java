package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record AgentSessionExecutionClaim(
        UUID sessionId, UUID turnId, UUID nodeRunId, String leaseOwnerId, long leaseToken,
        Instant leaseExpiresAt, String providerConversationId, String providerId, NodeContextMode contextMode,
        String providerVersion) {
    public AgentSessionExecutionClaim(UUID sessionId, UUID turnId, UUID nodeRunId, String leaseOwnerId,
                                      long leaseToken, Instant leaseExpiresAt, String providerConversationId,
                                      String providerId, NodeContextMode contextMode) {
        this(sessionId, turnId, nodeRunId, leaseOwnerId, leaseToken, leaseExpiresAt,
                providerConversationId, providerId, contextMode, null);
    }

    public AgentSessionExecutionClaim(UUID sessionId,UUID turnId,UUID nodeRunId,String leaseOwnerId,long leaseToken,
                                      Instant leaseExpiresAt,String providerConversationId,String providerId) {
        this(sessionId,turnId,nodeRunId,leaseOwnerId,leaseToken,leaseExpiresAt,providerConversationId,providerId,
                NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE, null);
    }
}
