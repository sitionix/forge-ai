package com.sitionix.forgeai.api.activeprofile;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ActiveLlmProfileUpdateRequest(
        @NotNull @Positive Long expectedRevision,
        @NotBlank String providerId,
        @NotBlank String modelId,
        @Valid ActiveLlmEffortRequest effort
) {

    @JsonAnySetter
    public void rejectUnknownField(final String fieldName, final String ignoredValue) {
        throw new IllegalArgumentException("Unknown active LLM profile field: " + fieldName);
    }
}
