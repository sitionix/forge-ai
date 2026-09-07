package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record AgentExecutionTurn(
        UUID id, UUID agentSessionId, UUID nodeRunId, String providerTurnId, int sequence,
        AgentExecutionTurnStatus status, String failureCode, String failureMessage,
        Instant startedAt, Instant finishedAt, Instant createdAt, Instant updatedAt) {
}
