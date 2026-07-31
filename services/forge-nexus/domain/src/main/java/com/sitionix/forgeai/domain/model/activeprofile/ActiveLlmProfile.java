package com.sitionix.forgeai.domain.model.activeprofile;

public record ActiveLlmProfile(
        String providerId,
        String modelId,
        LlmEffort effort
) {
    public ActiveLlmProfile {
        providerId = ActiveProfileInvariants.text(providerId, "providerId");
        modelId = ActiveProfileInvariants.text(modelId, "modelId");
    }
}
