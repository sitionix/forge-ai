package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;

public record KnowledgeAnalysisFilesView(
        List<KnowledgeAnalysisFileView> files,
        Integer total,
        Integer limit,
        Integer offset
) {
}
