package com.sitionix.forgeai.domain.model.generation;

import java.util.List;

public record GeneratedApiArtifact(
        String generationName,
        String scope,
        String dependency,
        Long runId,
        String workflowRunUrl,
        List<String> notes
) {
}
