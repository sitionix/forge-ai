package com.sitionix.forgeai.application.laneexecution.orchestration;

import java.util.List;

public record ApiArtifactGenerationTarget(
        String serviceId,
        String scope,
        String sourceRepo,
        String apiFamily,
        String serviceCode,
        List<String> generatedArtifacts,
        List<String> consumerArtifacts,
        List<String> frontendPackages
) {
}
