package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto;

public record KnowledgeActiveLlmProfileResponse(
        Long revision,
        KnowledgeActiveLlmProfileDetails llmProfile
) {
}
