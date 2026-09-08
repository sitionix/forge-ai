package com.sitionix.forgeai.api.agentproxy;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record AgentExecutionEventResponse(
        UUID id, UUID agentSessionId, UUID agentTurnId, UUID nodeRunId, long sequence,
        String type, String status, String phase, String providerEventKey, JsonNode payload,
        Instant occurredAt, Instant createdAt) {
}
