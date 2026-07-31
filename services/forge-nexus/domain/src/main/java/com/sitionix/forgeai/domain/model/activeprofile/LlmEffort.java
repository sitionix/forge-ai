package com.sitionix.forgeai.domain.model.activeprofile;

public record LlmEffort(String effortId) {
    public LlmEffort {
        effortId = ActiveProfileInvariants.text(effortId, "effortId");
    }
}
