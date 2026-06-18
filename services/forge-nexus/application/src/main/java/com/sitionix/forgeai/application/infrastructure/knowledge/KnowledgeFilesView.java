package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;

public record KnowledgeFilesView(
        List<KnowledgeViews.KnowledgeFileView> files,
        Integer limit,
        Integer offset,
        Integer total
) {
}
