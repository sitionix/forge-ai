package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

public record AgentDefinitionRequest(
        String name,
        String instructions,
        JsonNode outputSchema,
        List<UUID> dependsOnAgentIds
) {
}
