package com.sitionix.forgeai.domain.model.generation;

public record ApiArtifactGenerationRequest(
        String pullRequestUrl,
        String repository,
        String expectedArtifact,
        String scope,
        String generationType
) {
}
