package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record AgentDefinitionResponse(
        UUID id,
        UUID projectId,
        String name,
        String instructions,
        JsonNode outputSchema,
        AgentModelSelectionDto model,
        Instant createdAt,
        Instant updatedAt
) {
    public AgentDefinitionResponse(final UUID id,
                                   final UUID projectId,
                                   final String name,
                                   final String instructions,
                                   final JsonNode outputSchema,
                                   final Instant createdAt,
                                   final Instant updatedAt) {
        this(id, projectId, name, instructions, outputSchema, null, createdAt, updatedAt);
    }
}
