package com.sitionix.forgeai.application.infrastructure.knowledge;

public record KnowledgeInventoryStatusView(
        String status,
        String lastBuildAt,
        Integer sourceCount,
        Integer fileCount,
        Integer skippedCount
) {
}
