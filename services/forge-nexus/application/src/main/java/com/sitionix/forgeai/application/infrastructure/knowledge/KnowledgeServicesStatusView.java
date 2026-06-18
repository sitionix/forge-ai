package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;

public record KnowledgeServicesStatusView(
        List<KnowledgeServiceStatusView> services,
        KnowledgeAnalysisJobView activeJob
) {
}
