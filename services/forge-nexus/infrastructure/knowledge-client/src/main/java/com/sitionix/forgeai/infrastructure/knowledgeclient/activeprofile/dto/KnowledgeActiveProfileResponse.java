package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto;

public record KnowledgeActiveProfileResponse(
        Long revision,
        KnowledgeActiveLlmProfileDetails llmProfile,
        KnowledgeActiveEmbeddingProfile embeddingProfile,
        KnowledgeLlmUsage usage
) {
}
