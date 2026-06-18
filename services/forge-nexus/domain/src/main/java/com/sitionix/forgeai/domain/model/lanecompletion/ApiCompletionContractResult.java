package com.sitionix.forgeai.domain.model.lanecompletion;

import java.util.List;

public record ApiCompletionContractResult(
        String scope,
        String method,
        String path,
        String operationId,
        List<String> notes,
        List<ApiCompletionGeneratedArtifact> artifacts
) {
}
