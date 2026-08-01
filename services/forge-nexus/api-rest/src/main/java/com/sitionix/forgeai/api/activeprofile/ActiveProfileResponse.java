package com.sitionix.forgeai.api.activeprofile;

public record ActiveProfileResponse(
        long revision,
        ActiveLlmProfileDetailsResponse llmProfile,
        ActiveEmbeddingProfileResponse embeddingProfile,
        LlmUsageResponse usage
) {
}
