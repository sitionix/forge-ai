package com.sitionix.forgeai.api.activeprofile;

public record ActiveLlmProfileDetailsResponse(
        String providerId,
        String modelId,
        ActiveLlmEffortResponse effort,
        String providerDisplayName,
        String modelDisplayName
) {
    public ActiveLlmProfileDetailsResponse(final String providerId, final String modelId, final ActiveLlmEffortResponse effort) {
        this(providerId, modelId, effort, null, null);
    }
}
