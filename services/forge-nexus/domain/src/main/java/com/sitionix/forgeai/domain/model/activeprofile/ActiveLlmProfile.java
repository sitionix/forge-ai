package com.sitionix.forgeai.domain.model.activeprofile;

public record ActiveLlmProfile(
        String providerId,
        String modelId,
        LlmEffort effort,
        String providerDisplayName,
        String modelDisplayName
) {
    public ActiveLlmProfile(final String providerId, final String modelId, final LlmEffort effort) {
        this(providerId, modelId, effort, null, null);
    }
}
