package com.sitionix.forgeai.domain.model.activeprofile;

public record ActiveProfile(
        long revision,
        ActiveLlmProfile llmProfile,
        ActiveEmbeddingProfile embeddingProfile,
        LlmUsage usage
) {
}
