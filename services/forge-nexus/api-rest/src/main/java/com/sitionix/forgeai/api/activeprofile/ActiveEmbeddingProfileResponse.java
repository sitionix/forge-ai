package com.sitionix.forgeai.api.activeprofile;

public record ActiveEmbeddingProfileResponse(
        String providerId,
        String modelId,
        String status,
        String providerVersion,
        Integer embeddingDimension,
        String lastCheckedAt,
        ActiveEmbeddingDiagnosticResponse diagnostic
) {
}
