package com.sitionix.forgeai.domain.model.activeprofile;

public record ActiveLlmProfileUpdateResult(
        long revision,
        ActiveLlmSelection llmProfile
) {
}
