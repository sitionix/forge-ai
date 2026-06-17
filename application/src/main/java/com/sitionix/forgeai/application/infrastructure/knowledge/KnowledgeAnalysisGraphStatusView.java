package com.sitionix.forgeai.application.infrastructure.knowledge;

public record KnowledgeAnalysisGraphStatusView(
        String analysisStatus,
        String jobId,
        String engineVersion,
        Integer processedFileCount,
        Integer fileCount,
        Integer failedFileCount,
        Double progressPercent,
        String currentFile,
        Integer trustedFactsCount,
        Integer diagnosticsCount,
        String lastUpdatedAt
) {
}
