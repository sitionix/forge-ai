package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;

public record KnowledgeAnalysisSymbolsView(
        List<KnowledgeAnalysisSymbolView> symbols,
        Integer total,
        Integer limit,
        Integer offset
) {
}
