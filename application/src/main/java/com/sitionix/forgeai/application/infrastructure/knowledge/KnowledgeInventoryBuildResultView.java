package com.sitionix.forgeai.application.infrastructure.knowledge;

public record KnowledgeInventoryBuildResultView(
        String status,
        Integer sourceCount,
        Integer fileCount,
        Integer skippedCount,
        KnowledgeSkippedBreakdownView skippedBreakdown,
        String startedAt,
        String completedAt
) {
}
