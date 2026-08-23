package com.sitionix.forgeai.api.agentproxy;

import com.fasterxml.jackson.databind.JsonNode;

public record AgentDefinitionRequest(
        String name,
        String instructions,
        JsonNode outputSchema,
        AgentModelSelectionRequest model
) {
}
