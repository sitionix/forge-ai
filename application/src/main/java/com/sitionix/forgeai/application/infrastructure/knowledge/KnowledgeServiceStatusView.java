package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;

public record KnowledgeServiceStatusView(
        String sourceId,
        String label,
        String displayName,
        String group,
        String path,
        Boolean rootExists,
        List<String> tags,
        KnowledgeServiceInventoryView inventory,
        KnowledgeServiceAnalysisView analysis,
        KnowledgeServiceFactsView facts,
        List<KnowledgeDiagnosticView> diagnostics
) {
}
