package com.sitionix.forgeai.domain.model.activeprofile;

public record ActiveEmbeddingProfile(
        String providerId,
        String modelId,
        String status,
        String providerVersion,
        Integer embeddingDimension,
        String lastCheckedAt,
        ActiveEmbeddingDiagnostic diagnostic
) {
}
