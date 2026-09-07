package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record AgentExecutionEvent(
        UUID id,
        UUID agentSessionId,
        UUID agentTurnId,
        UUID nodeRunId,
        long sequence,
        AgentExecutionEventType type,
        AgentExecutionEventStatus status,
        String phase,
        String providerEventKey,
        String payload,
        Instant occurredAt,
        Instant createdAt) {
}
