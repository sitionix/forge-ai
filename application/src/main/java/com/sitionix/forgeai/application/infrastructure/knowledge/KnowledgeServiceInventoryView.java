package com.sitionix.forgeai.application.infrastructure.knowledge;

public record KnowledgeServiceInventoryView(
        String status,
        Integer eligibleFileCount,
        Integer skippedCount,
        KnowledgeSkippedBreakdownView skippedBreakdown,
        String lastInventoryAt
) {
}
