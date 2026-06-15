package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;
import java.util.Map;

public record KnowledgeAnalysisSymbolView(
        String symbolId,
        String sourceId,
        String relativePath,
        String name,
        String kind,
        List<KnowledgeAnalysisSymbolRoleView> roles,
        Integer lineStart,
        Integer lineEnd,
        String summary,
        Map<String, Object> metadata
) {
}
