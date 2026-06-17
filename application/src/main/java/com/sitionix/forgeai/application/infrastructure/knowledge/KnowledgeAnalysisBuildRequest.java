package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;

public record KnowledgeAnalysisBuildRequest(
        List<String> sourceIds,
        List<String> groups,
        Boolean force,
        Integer maxFiles,
        Integer concurrency
) {
}
