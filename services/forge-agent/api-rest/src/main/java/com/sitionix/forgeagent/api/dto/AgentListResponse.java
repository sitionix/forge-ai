package com.sitionix.forgeagent.api.dto;

import java.time.Instant;
import java.util.UUID;

public record AgentListResponse(
        UUID id,
        UUID projectId,
        String name,
        AgentModelSelectionResponse model,
        Instant createdAt,
        Instant updatedAt
) {
}
