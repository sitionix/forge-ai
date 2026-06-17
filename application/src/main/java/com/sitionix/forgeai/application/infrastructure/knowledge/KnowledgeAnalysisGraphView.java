package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;
import java.util.Map;

public record KnowledgeAnalysisGraphView(
        String sourceId,
        String sourceName,
        KnowledgeAnalysisGraphStatusView status,
        Map<String, Object> filters,
        List<Map<String, Object>> nodes,
        List<Map<String, Object>> edges,
        List<Map<String, Object>> claims,
        List<Map<String, Object>> evidence,
        Map<String, Object> selected,
        List<Map<String, Object>> groups,
        List<Map<String, Object>> uncertainties,
        List<Map<String, Object>> diagnostics,
        Map<String, Object> metrics,
        KnowledgeAnalysisGraphMetaView meta
) {
}
