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
        Map<String, Object> metadata,
        String graphEdgeId,
        String fromGraphNodeId,
        String toGraphNodeId,
        String edgeType,
        String resolutionStatus,
        String factStatus,
        String factOrigin,
        String flowDomain,
        Map<String, Object> unresolvedTarget,
        Integer evidenceCount,
        Integer diagnosticCount
) {
}
