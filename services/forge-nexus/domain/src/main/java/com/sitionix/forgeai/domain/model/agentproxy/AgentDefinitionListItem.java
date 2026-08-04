package com.sitionix.forgeai.domain.model.agentproxy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentDefinitionListItem(
        UUID id,
        UUID projectId,
        String name,
        List<AgentDependencySummary> dependsOn,
        Instant createdAt,
        Instant updatedAt
) {
}
