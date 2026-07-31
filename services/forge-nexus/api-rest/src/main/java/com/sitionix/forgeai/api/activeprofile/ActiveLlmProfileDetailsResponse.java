package com.sitionix.forgeai.api.activeprofile;

public record ActiveLlmProfileDetailsResponse(
        String providerId,
        String modelId,
        ActiveLlmEffortResponse effort
) {
}
