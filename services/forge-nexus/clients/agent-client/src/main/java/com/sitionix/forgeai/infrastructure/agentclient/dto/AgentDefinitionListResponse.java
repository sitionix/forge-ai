package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.time.Instant;
import java.util.UUID;

public record AgentDefinitionListResponse(
        UUID id,
        UUID projectId,
        String name,
        AgentModelSelectionDto model,
        Instant createdAt,
        Instant updatedAt
) {
}
