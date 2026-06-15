package com.sitionix.forgeai.application.infrastructure.knowledge;

public record KnowledgeAnalysisSymbolsRequest(
        String sourceId,
        String role,
        String kind,
        String pathContains,
        String nameContains,
        Integer limit,
        Integer offset
) {
}
