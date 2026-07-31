package com.sitionix.forgeai.api.activeprofile;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ActiveLlmEffortRequest(@NotBlank String effortId) {

    @JsonAnySetter
    public void rejectUnknownField(final String fieldName, final String ignoredValue) {
        throw new IllegalArgumentException("Unknown active LLM effort field: " + fieldName);
    }
}
