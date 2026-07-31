package com.sitionix.forgeai.domain.model.activeprofile;

public record ActiveLlmProfileUpdateResult(
        long revision,
        ActiveLlmProfile llmProfile
) {
    public ActiveLlmProfileUpdateResult {
        revision = ActiveProfileInvariants.positive(revision, "revision");
        llmProfile = ActiveProfileInvariants.required(llmProfile, "llmProfile");
    }
}
