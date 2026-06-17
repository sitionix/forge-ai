package com.sitionix.forgeai.application.infrastructure.knowledge;

public record KnowledgeServiceDetailsView(
        KnowledgeAnalysisSymbolsView symbols,
        KnowledgeAnalysisRelationsView relations,
        KnowledgeAnalysisFilesView failures
) {
}
