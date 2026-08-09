package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record AgentDetails(
        UUID id,
        UUID projectId,
        String name,
        String instructions,
        AgentOutputSchema outputSchema,
        Instant createdAt,
        Instant updatedAt
) {
}
