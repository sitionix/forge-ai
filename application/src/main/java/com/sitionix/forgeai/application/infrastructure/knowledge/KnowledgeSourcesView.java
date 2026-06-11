package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;

public record KnowledgeSourcesView(
        KnowledgeViews.KnowledgeCatalogView catalog,
        List<KnowledgeViews.KnowledgeSourceView> sources,
        List<KnowledgeViews.KnowledgeDiagnosticView> diagnostics,
        String message
) {
}
