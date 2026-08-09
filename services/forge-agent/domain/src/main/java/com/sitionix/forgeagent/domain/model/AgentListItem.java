package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentListItem(
        UUID id,
        UUID projectId,
        String name,
        List<AgentDependencySummary> dependsOn,
        Instant createdAt,
        Instant updatedAt
) {
}
