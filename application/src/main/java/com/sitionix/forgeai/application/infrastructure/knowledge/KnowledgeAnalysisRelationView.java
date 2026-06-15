package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;
import java.util.Map;

public record KnowledgeAnalysisRelationView(
        String relationId,
        String sourceId,
        String fromSymbolId,
        String toSymbolId,
        String relation,
        Double confidence,
        List<String> evidence,
        Integer lineStart,
        Integer lineEnd,
        Map<String, Object> metadata
) {
}
