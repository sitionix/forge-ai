package com.sitionix.forgeai.domain.model.operator.service;

import java.util.List;

public record OperatorServiceContractReference(
        String refKey,
        String sourceRepo,
        String sourcePath,
        boolean sourceExists,
        String apiFamily,
        String eventFamily,
        String serviceCode,
        String root,
        List<String> schemas,
        List<String> operations,
        List<String> topics,
        List<String> payloads,
        List<String> generatedArtifacts,
        List<String> consumerArtifacts,
        List<String> frontendPackages
) {
}
