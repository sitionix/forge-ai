package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record AgentExecutionSession(
        UUID id, UUID workflowRunId, UUID sourceNodeId, UUID sourceAgentId, UUID repositoryId,
        String providerId, String providerConversationId, String providerVersion, NodeContextMode contextMode,
        AgentExecutionSessionStatus status, AgentExecutionTerminalOutcome terminalOutcome, UUID activeNodeRunId,
        String leaseOwnerId, long leaseToken, Instant leaseExpiresAt, String failureCode, String failureMessage,
        Instant createdAt, Instant updatedAt, Instant closedAt, Instant contextResetAt,
        UUID contextIterationId) {

    public AgentExecutionSession(UUID id, UUID workflowRunId, UUID sourceNodeId, UUID sourceAgentId, UUID repositoryId,
        String providerId, String providerConversationId, String providerVersion, NodeContextMode contextMode,
        AgentExecutionSessionStatus status, AgentExecutionTerminalOutcome terminalOutcome, UUID activeNodeRunId,
        String leaseOwnerId, long leaseToken, Instant leaseExpiresAt, String failureCode, String failureMessage,
        Instant createdAt, Instant updatedAt, Instant closedAt, Instant contextResetAt) {
        this(id, workflowRunId, sourceNodeId, sourceAgentId, repositoryId, providerId, providerConversationId,
                providerVersion, contextMode, status, terminalOutcome, activeNodeRunId, leaseOwnerId, leaseToken,
                leaseExpiresAt, failureCode, failureMessage, createdAt, updatedAt, closedAt, contextResetAt, null);
    }
}
