package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;

public record KnowledgeAnalysisRelationsView(
        List<KnowledgeAnalysisRelationView> relations,
        Integer total,
        Integer limit,
        Integer offset
) {
}
