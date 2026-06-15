package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;
import java.util.Map;

public final class KnowledgeViews {

    private KnowledgeViews() {
    }

    public record KnowledgeCatalogView(Boolean configured, String type) {
    }

    public record KnowledgeInventorySummaryView(
            Boolean implemented,
            String status,
            String lastBuildAt,
            Integer sourceCount,
            Integer fileCount,
            Integer skippedCount,
            KnowledgeSkippedBreakdownView skippedBreakdown
    ) {
    }

    public record KnowledgeCoverageView(
            Integer scannedFiles,
            Integer eligibleFiles,
            String completedAt
    ) {
    }

    public record KnowledgeFreshnessView(
            String status,
            String checkedAt,
            Integer newFiles,
            Integer modifiedFiles,
            Integer deletedFiles,
            Integer affectedScannedFiles
    ) {
    }

    public record KnowledgeSourceView(
            String sourceId,
            String displayName,
            String group,
            String path,
            Boolean rootExists,
            List<String> tags,
            List<String> domainKeywords,
            List<String> ownsBusinessAreas,
            List<String> tests
    ) {
    }

    public record KnowledgeDiagnosticView(String sourceId, String code, String message) {
    }

    public record KnowledgeFileView(
            String sourceId,
            String sourcePath,
            String relativePath,
            String extension,
            Long sizeBytes,
            String contentHash,
            String lastModified
    ) {
    }
}
