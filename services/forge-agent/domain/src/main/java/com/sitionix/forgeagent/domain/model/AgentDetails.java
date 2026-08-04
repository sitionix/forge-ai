package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentDetails(
        UUID id,
        UUID projectId,
        String name,
        String instructions,
        AgentOutputSchema outputSchema,
        List<AgentDependencySummary> dependsOn,
        Instant createdAt,
        Instant updatedAt
) {
}
