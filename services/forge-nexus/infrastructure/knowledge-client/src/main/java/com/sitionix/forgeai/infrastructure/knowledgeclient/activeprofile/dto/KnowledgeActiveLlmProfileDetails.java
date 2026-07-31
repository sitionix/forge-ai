package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto;

public record KnowledgeActiveLlmProfileDetails(
        String providerId,
        String modelId,
        KnowledgeActiveLlmEffort effort
) {
    public KnowledgeActiveLlmProfileDetails {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId is required");
        }
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId is required");
        }
    }
}
