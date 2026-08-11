package com.sitionix.forgeagent.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record AgentResponse(
        UUID id,
        UUID projectId,
        String name,
        String instructions,
        JsonNode outputSchema,
        AgentModelSelectionResponse model,
        Instant createdAt,
        Instant updatedAt
) {
    public AgentResponse(final UUID id,
                         final UUID projectId,
                         final String name,
                         final String instructions,
                         final JsonNode outputSchema,
                         final Instant createdAt,
                         final Instant updatedAt) {
        this(id, projectId, name, instructions, outputSchema, null, createdAt, updatedAt);
    }
}
