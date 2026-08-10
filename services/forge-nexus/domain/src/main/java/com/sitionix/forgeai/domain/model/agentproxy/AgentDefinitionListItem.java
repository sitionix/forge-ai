package com.sitionix.forgeai.domain.model.agentproxy;

import java.time.Instant;
import java.util.UUID;

public record AgentDefinitionListItem(
        UUID id,
        UUID projectId,
        String name,
        Instant createdAt,
        Instant updatedAt
) {
}
