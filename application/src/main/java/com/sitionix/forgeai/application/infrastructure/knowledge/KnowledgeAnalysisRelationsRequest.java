package com.sitionix.forgeai.application.infrastructure.knowledge;

public record KnowledgeAnalysisRelationsRequest(
        String sourceId,
        String relation,
        String fromSymbolId,
        String toSymbolId,
        String flowDomain,
        String factOrigin,
        Integer limit,
        Integer offset
) {
    public KnowledgeAnalysisRelationsRequest(final String sourceId,
                                             final String relation,
                                             final String fromSymbolId,
                                             final String toSymbolId,
                                             final Integer limit,
                                             final Integer offset) {
        this(sourceId, relation, fromSymbolId, toSymbolId, null, null, limit, offset);
    }
}
