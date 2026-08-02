package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto;

public record KnowledgeActiveLlmSelection(
        String providerId,
        String modelId,
        KnowledgeActiveLlmEffort effort
) {
}
