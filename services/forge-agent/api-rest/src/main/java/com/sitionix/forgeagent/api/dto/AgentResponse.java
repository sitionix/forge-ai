package com.sitionix.forgeagent.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentResponse(
        UUID id,
        UUID projectId,
        String name,
        String instructions,
        JsonNode outputSchema,
        List<AgentDependencyResponse> dependsOn,
        Instant createdAt,
        Instant updatedAt
) {
}
