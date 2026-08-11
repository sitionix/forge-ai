package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record AgentDefinition(
        UUID id,
        UUID projectId,
        String name,
        String normalizedName,
        String instructions,
        AgentOutputSchema outputSchema,
        AgentModelSelection model,
        Instant createdAt,
        Instant updatedAt
) {
    public AgentDefinition(final UUID id,
                           final UUID projectId,
                           final String name,
                           final String normalizedName,
                           final String instructions,
                           final AgentOutputSchema outputSchema,
                           final Instant createdAt,
                           final Instant updatedAt) {
        this(id, projectId, name, normalizedName, instructions, outputSchema, null, createdAt, updatedAt);
    }
}
