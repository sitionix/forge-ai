package com.sitionix.forgeai.api.activeprofile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ActiveLlmProfileUpdateRequest(
        Long expectedRevision,
        String providerId,
        String modelId,
        ActiveLlmEffortRequest effort
) {
}
