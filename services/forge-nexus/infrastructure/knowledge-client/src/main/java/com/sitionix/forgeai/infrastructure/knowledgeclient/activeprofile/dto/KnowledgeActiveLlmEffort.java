package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto;

public record KnowledgeActiveLlmEffort(String effortId) {
    public KnowledgeActiveLlmEffort {
        if (effortId == null || effortId.isBlank()) {
            throw new IllegalArgumentException("effortId is required");
        }
    }
}
