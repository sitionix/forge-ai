package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto;

public record KnowledgeActiveLlmProfileResponse(
        Long revision,
        KnowledgeActiveLlmSelection llmProfile
) {
}
