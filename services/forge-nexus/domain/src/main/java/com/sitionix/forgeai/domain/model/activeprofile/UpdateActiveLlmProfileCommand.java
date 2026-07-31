package com.sitionix.forgeai.domain.model.activeprofile;

public record UpdateActiveLlmProfileCommand(
        long expectedRevision,
        String providerId,
        String modelId,
        LlmEffort effort
) {
    public UpdateActiveLlmProfileCommand {
        expectedRevision = ActiveProfileInvariants.positive(expectedRevision, "expectedRevision");
        providerId = ActiveProfileInvariants.text(providerId, "providerId");
        modelId = ActiveProfileInvariants.text(modelId, "modelId");
    }
}
