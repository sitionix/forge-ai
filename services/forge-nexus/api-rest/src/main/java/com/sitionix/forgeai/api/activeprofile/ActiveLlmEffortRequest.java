package com.sitionix.forgeai.api.activeprofile;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;

public record ActiveLlmEffortRequest(@NotBlank String effortId) {

    @JsonAnySetter
    public void rejectUnknownField(final String fieldName, final Object ignoredValue) {
        throw new IllegalArgumentException("Unknown active LLM effort field: " + fieldName);
    }
}
