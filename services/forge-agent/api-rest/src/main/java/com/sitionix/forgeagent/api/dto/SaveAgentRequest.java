package com.sitionix.forgeagent.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SaveAgentRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank String instructions,
        @NotNull JsonNode outputSchema,
        AgentModelSelectionRequest model
) {
    public SaveAgentRequest(final String name, final String instructions, final JsonNode outputSchema) {
        this(name, instructions, outputSchema, null);
    }
}
