package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto;

public record KnowledgeActiveLlmProfileRequest(
        long expectedRevision,
        String providerId,
        String modelId,
        KnowledgeActiveLlmEffort effort
) {
}
