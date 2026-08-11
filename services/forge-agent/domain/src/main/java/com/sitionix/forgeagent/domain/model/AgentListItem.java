package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record AgentListItem(
        UUID id,
        UUID projectId,
        String name,
        AgentModelSelection model,
        Instant createdAt,
        Instant updatedAt
) {
}
