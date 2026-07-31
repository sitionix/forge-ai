package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto;

public record KnowledgeActiveLlmProfileResponse(
        Long revision,
        KnowledgeActiveLlmProfileDetails llmProfile
) {
    public KnowledgeActiveLlmProfileResponse {
        if (revision == null || revision <= 0) {
            throw new IllegalArgumentException("revision must be present and positive");
        }
        if (llmProfile == null) {
            throw new IllegalArgumentException("llmProfile is required");
        }
    }
}
