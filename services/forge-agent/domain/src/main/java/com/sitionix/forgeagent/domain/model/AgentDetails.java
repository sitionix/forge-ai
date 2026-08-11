package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record AgentDetails(
        UUID id,
        UUID projectId,
        String name,
        String instructions,
        AgentOutputSchema outputSchema,
        AgentModelSelection model,
        Instant createdAt,
        Instant updatedAt
) {
    public AgentDetails(final UUID id,
                        final UUID projectId,
                        final String name,
                        final String instructions,
                        final AgentOutputSchema outputSchema,
                        final Instant createdAt,
                        final Instant updatedAt) {
        this(id, projectId, name, instructions, outputSchema, null, createdAt, updatedAt);
    }
}
