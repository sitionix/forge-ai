package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto;

public record KnowledgeActiveProfileResponse(
        Long revision,
        KnowledgeActiveLlmProfileDetails llmProfile,
        KnowledgeLlmUsage usage
) {
}
