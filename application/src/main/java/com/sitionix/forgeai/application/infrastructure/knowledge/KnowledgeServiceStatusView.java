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
        List<KnowledgeDiagnosticView> diagnostics,
        KnowledgeServiceDetailsView details
) {
    public KnowledgeServiceStatusView(final String sourceId,
                                      final String label,
                                      final String displayName,
                                      final String group,
                                      final String path,
                                      final Boolean rootExists,
                                      final List<String> tags,
                                      final KnowledgeServiceInventoryView inventory,
                                      final KnowledgeServiceAnalysisView analysis,
                                      final KnowledgeServiceFactsView facts,
                                      final List<KnowledgeDiagnosticView> diagnostics) {
        this(sourceId, label, displayName, group, path, rootExists, tags, inventory, analysis, facts, diagnostics, null);
    }
}
