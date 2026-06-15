package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;

public record KnowledgeAnalysisFileView(
        String sourceId,
        String relativePath,
        String contentHash,
        String analyzerVersion,
        String analysisStatus,
        String analyzedAt,
        Integer symbolCount,
        Integer relationCount,
        Integer attemptCount,
        String lastAttemptAt,
        String lastErrorCode,
        String lastErrorMessage,
        String lastRawResponsePreview,
        List<KnowledgeDiagnosticView> diagnostics
) {
}
