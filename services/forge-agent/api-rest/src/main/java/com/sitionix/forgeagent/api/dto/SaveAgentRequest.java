package com.sitionix.forgeagent.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record SaveAgentRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank String instructions,
        @NotNull JsonNode outputSchema,
        List<UUID> dependsOnAgentIds
) {
}
