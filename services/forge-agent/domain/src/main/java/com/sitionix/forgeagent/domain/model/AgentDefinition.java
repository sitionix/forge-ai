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
        Instant createdAt,
        Instant updatedAt
) {
}
