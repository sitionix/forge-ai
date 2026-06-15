package com.sitionix.forgeai.application.infrastructure.knowledge;

public record KnowledgeAnalysisSymbolsRequest(
        String sourceId,
        String role,
        String kind,
        String pathContains,
        String nameContains,
        String flowDomain,
        String factOrigin,
        Integer limit,
        Integer offset
) {
    public KnowledgeAnalysisSymbolsRequest(final String sourceId,
                                           final String role,
                                           final String kind,
                                           final String pathContains,
                                           final String nameContains,
                                           final Integer limit,
                                           final Integer offset) {
        this(sourceId, role, kind, pathContains, nameContains, null, null, limit, offset);
    }
}
