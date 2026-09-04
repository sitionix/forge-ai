package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record AgentExecutionSession(
        UUID id, UUID workflowRunId, UUID sourceNodeId, UUID sourceAgentId, UUID repositoryId,
        String providerId, String providerConversationId, String providerVersion, NodeContextMode contextMode,
        AgentExecutionSessionStatus status, AgentExecutionTerminalOutcome terminalOutcome, UUID activeNodeRunId,
        String leaseOwnerId, long leaseToken, Instant leaseExpiresAt, String failureCode, String failureMessage,
        Instant createdAt, Instant updatedAt, Instant closedAt) {
}
