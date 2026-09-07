package com.sitionix.forgeai.domain.model.agentproxy;

import java.time.Instant;
import java.util.UUID;

public record AgentExecutionEvent(
        UUID id, UUID agentSessionId, UUID agentTurnId, UUID nodeRunId, long sequence,
        String type, String status, String phase, String providerEventKey, String payload,
        Instant occurredAt, Instant createdAt) {
}
