package com.sitionix.forgeagent.domain.model;

import java.time.Instant;

public record AgentExecutionEventCandidate(
        AgentExecutionEventType type,
        AgentExecutionEventStatus status,
        String phase,
        String providerEventKey,
        String payload,
        Instant occurredAt) {

    public AgentExecutionEventCandidate {
        if (type == null) throw new IllegalArgumentException("type is required");
        if (payload == null || payload.isBlank()) throw new IllegalArgumentException("payload is required");
        if (occurredAt == null) throw new IllegalArgumentException("occurredAt is required");
        if (providerEventKey != null && providerEventKey.isBlank()) {
            throw new IllegalArgumentException("providerEventKey must be null or nonblank");
        }
    }
}
