package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;

public record KnowledgeAnalysisSymbolRoleView(
        String role,
        Double confidence,
        List<String> evidence,
        String classifier,
        String classifierVersion
) {
}
