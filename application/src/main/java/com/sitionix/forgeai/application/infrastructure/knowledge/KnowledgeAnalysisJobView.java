package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;

public record KnowledgeAnalysisJobView(
        String jobId,
        String status,
        String startedAt,
        String completedAt,
        Integer sourceCount,
        Integer fileCount,
        Integer processedFileCount,
        Integer failedFileCount,
        String currentSourceId,
        String currentRelativePath,
        List<String> sourceIds,
        String lastProgressAt,
        Integer symbolCount,
        Integer relationCount,
        List<KnowledgeDiagnosticView> diagnostics
) {
}
