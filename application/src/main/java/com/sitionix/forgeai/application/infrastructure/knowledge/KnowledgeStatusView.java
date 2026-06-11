package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.Map;

public record KnowledgeStatusView(
        String status,
        String module,
        KnowledgeViews.KnowledgeCatalogView catalog,
        Map<String, Object> inventory,
        Map<String, Object> search,
        Map<String, Object> vectorStore,
        Map<String, Object> rag,
        String message
) {
}
