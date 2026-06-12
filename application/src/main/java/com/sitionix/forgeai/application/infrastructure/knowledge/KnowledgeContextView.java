package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;

public record KnowledgeContextView(
        String query,
        List<KnowledgeContextItemView> context,
        List<KnowledgeContextSourceView> sourcesUsed,
        KnowledgeContextBudgetView budget,
        List<KnowledgeDiagnosticView> diagnostics
) {
}
