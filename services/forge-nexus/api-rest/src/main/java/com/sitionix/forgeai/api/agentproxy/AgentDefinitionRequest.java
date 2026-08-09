package com.sitionix.forgeai.api.agentproxy;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AgentDefinitionRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank String instructions,
        @NotNull JsonNode outputSchema
) {
}
