package com.sitionix.forgeai.domain.model.activeprofile;

public record ActiveProfile(
        long revision,
        ActiveLlmProfile llmProfile,
        LlmUsage usage
) {
    public ActiveProfile {
        revision = ActiveProfileInvariants.positive(revision, "revision");
        llmProfile = ActiveProfileInvariants.required(llmProfile, "llmProfile");
    }
}
