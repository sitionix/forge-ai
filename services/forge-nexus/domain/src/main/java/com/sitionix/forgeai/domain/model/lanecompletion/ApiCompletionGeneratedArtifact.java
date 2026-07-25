package com.sitionix.forgeai.domain.model.lanecompletion;

import java.util.List;

public record ApiCompletionGeneratedArtifact(
        String dependency,
        String role,
        String kind,
        Long runId,
        List<String> notes
) {
}
