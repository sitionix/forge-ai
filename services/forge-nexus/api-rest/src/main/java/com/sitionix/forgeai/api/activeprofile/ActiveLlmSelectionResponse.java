package com.sitionix.forgeai.api.activeprofile;

public record ActiveLlmSelectionResponse(
        String providerId,
        String modelId,
        ActiveLlmEffortResponse effort
) {
}
