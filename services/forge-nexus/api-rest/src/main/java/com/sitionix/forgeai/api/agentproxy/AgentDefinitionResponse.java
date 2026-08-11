package com.sitionix.forgeai.api.agentproxy;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record AgentDefinitionResponse(
        UUID id,
        UUID projectId,
        String name,
        String instructions,
        JsonNode outputSchema,
        AgentModelSelectionResponse model,
        Instant createdAt,
        Instant updatedAt
) {
}
