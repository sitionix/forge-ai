package com.sitionix.forgeai.domain.model.agentproxy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentDefinitionDetails(
        UUID id,
        UUID projectId,
        String name,
        String instructions,
        AgentOutputSchemaDocument outputSchema,
        List<AgentDependencySummary> dependsOn,
        Instant createdAt,
        Instant updatedAt
) {
}
