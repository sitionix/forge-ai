package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Workflow(
        UUID id,
        UUID projectId,
        String name,
        String normalizedName,
        List<Node> nodes,
        Instant createdAt,
        Instant updatedAt
) {
}
