package com.sitionix.forgeai.api.activeprofile;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ActiveLlmProfileUpdateRequest(
        @NotNull @Positive Long expectedRevision,
        @NotBlank String providerId,
        @NotBlank String modelId,
        @Valid ActiveLlmEffortRequest effort
) {

    @JsonAnySetter
    public void rejectUnknownField(final String fieldName, final Object ignoredValue) {
        throw new IllegalArgumentException("Unknown active LLM profile field: " + fieldName);
    }
}
