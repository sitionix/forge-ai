package com.sitionix.forgeai.api.activeprofile;

public record ActiveProfileResponse(
        long revision,
        ActiveLlmProfileDetailsResponse llmProfile,
        LlmUsageResponse usage
) {
}
