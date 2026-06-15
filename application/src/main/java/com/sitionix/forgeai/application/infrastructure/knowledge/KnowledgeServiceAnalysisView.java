package com.sitionix.forgeai.application.infrastructure.knowledge;

public record KnowledgeServiceAnalysisView(
        String status,
        Integer inventoryFileCount,
        Integer analyzedFileCount,
        Double percent,
        Integer processedFileCount,
        Integer failedFileCount,
        Integer pendingFileCount,
        Integer staleFileCount,
        Integer skippedTooLargeFileCount,
        String currentRelativePath,
        String lastProgressAt,
        String activeJobId
) {
}
