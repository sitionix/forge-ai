package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto;

public record KnowledgeActiveLlmProfileDetails(
        String providerId,
        String modelId,
        KnowledgeActiveLlmEffort effort
) {
}
