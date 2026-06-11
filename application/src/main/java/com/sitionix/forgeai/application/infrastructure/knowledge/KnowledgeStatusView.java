package com.sitionix.forgeai.application.infrastructure.knowledge;

public record KnowledgeStatusView(
        String status,
        String module,
        KnowledgeViews.KnowledgeCatalogView catalog,
        KnowledgeViews.KnowledgeInventorySummaryView inventory,
        KnowledgeViews.KnowledgeFeatureView search,
        KnowledgeViews.KnowledgeFeatureView vectorStore,
        KnowledgeViews.KnowledgeFeatureView rag,
        String message
) {
}
