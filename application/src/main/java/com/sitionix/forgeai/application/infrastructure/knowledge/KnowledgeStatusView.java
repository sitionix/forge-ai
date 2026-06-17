package com.sitionix.forgeai.application.infrastructure.knowledge;

public record KnowledgeStatusView(
        String status,
        String module,
        KnowledgeViews.KnowledgeCatalogView catalog,
        KnowledgeViews.KnowledgeInventorySummaryView inventory,
        KnowledgeViews.KnowledgeInventoryRefreshView inventoryRefresh,
        KnowledgeViews.KnowledgeCoverageView coverage,
        KnowledgeViews.KnowledgeFreshnessView freshness,
        String message
) {
}
