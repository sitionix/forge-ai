package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;

public record KnowledgeSearchResultView(
        String query,
        List<KnowledgeViews.KnowledgeSearchMatchView> results,
        String message
) {
}
