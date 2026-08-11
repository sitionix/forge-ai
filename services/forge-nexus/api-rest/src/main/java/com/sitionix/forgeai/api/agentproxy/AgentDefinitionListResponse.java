package com.sitionix.forgeai.api.agentproxy;

import java.time.Instant;
import java.util.UUID;

public record AgentDefinitionListResponse(
        UUID id,
        UUID projectId,
        String name,
        AgentModelSelectionResponse model,
        Instant createdAt,
        Instant updatedAt
) {
}
