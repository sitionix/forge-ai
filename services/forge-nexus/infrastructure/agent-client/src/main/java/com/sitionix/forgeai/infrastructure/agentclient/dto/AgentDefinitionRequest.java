package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record AgentDefinitionRequest(
        String name,
        String instructions,
        JsonNode outputSchema,
        AgentModelSelectionDto model
) {
    public AgentDefinitionRequest(final String name, final String instructions, final JsonNode outputSchema) {
        this(name, instructions, outputSchema, null);
    }
}
