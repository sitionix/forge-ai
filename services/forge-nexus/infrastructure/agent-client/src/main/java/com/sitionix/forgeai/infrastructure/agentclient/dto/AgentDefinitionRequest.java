package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record AgentDefinitionRequest(
        String name,
        String instructions,
        JsonNode outputSchema
) {
}
