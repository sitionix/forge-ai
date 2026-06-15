package com.sitionix.forgeai.application.infrastructure.knowledge;

public record KnowledgeAnalysisFilesRequest(
        String sourceId,
        String status,
        String pathContains,
        Integer limit,
        Integer offset
) {
}
