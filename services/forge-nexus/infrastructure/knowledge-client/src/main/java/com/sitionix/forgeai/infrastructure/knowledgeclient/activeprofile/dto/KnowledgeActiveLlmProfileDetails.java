package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto;

public record KnowledgeActiveLlmProfileDetails(
        String providerId,
        String modelId,
        KnowledgeActiveLlmEffort effort,
        String providerDisplayName,
        String modelDisplayName
) {
    public KnowledgeActiveLlmProfileDetails(final String providerId, final String modelId, final KnowledgeActiveLlmEffort effort) {
        this(providerId, modelId, effort, null, null);
    }
}
