package com.sitionix.forgeai.application.infrastructure.knowledge;

public record KnowledgeAnalysisRelationsRequest(
        String sourceId,
        String relation,
        String fromSymbolId,
        String toSymbolId,
        Integer limit,
        Integer offset
) {
}
