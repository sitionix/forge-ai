package com.sitionix.forgeai.api.activeprofile;

public record ActiveLlmProfileResponse(
        long revision,
        ActiveLlmProfileDetailsResponse llmProfile
) {
}
