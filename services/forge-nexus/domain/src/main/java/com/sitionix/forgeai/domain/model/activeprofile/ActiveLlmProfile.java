package com.sitionix.forgeai.domain.model.activeprofile;

public record ActiveLlmProfile(
        String providerId,
        String modelId,
        LlmEffort effort
) {
}
