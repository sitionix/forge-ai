package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto;

public record KnowledgeActiveEmbeddingProfile(
        String providerId,
        String modelId,
        String status,
        String providerVersion,
        Integer embeddingDimension,
        String lastCheckedAt,
        KnowledgeActiveEmbeddingDiagnostic diagnostic
) {
}
