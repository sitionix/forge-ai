package com.sitionix.forgeai.application.infrastructure.knowledge;

public record KnowledgeAnalysisStatusView(
        String status,
        String latestJobId,
        KnowledgeAnalysisJobView activeJob,
        String lastCompletedAt,
        Integer sourceCount,
        Integer fileCount,
        Integer scannedFileCount,
        Integer failedFileCount,
        Integer skippedUnchangedFileCount,
        Integer symbolCount,
        Integer relationCount,
        KnowledgeViews.KnowledgeFreshnessView freshness
) {
}
