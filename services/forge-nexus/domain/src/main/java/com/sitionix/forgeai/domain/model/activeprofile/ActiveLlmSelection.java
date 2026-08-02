package com.sitionix.forgeai.domain.model.activeprofile;

public record ActiveLlmSelection(
        String providerId,
        String modelId,
        LlmEffort effort
) {
}
