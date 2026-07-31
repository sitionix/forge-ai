package com.sitionix.forgeai.domain.model.activeprofile;

public record UpdateActiveLlmProfileCommand(
        long expectedRevision,
        String providerId,
        String modelId,
        LlmEffort effort
) {
}
