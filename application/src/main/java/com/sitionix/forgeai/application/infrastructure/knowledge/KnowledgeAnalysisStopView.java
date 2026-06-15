package com.sitionix.forgeai.application.infrastructure.knowledge;

public record KnowledgeAnalysisStopView(
        String jobId,
        String status,
        String message
) {
}
