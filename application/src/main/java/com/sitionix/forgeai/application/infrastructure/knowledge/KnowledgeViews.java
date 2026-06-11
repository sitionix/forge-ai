package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;
import java.util.Map;

public final class KnowledgeViews {

    private KnowledgeViews() {
    }

    public record KnowledgeCatalogView(Boolean configured, String type) {
    }

    public record KnowledgeInventorySummaryView(Boolean implemented, String lastBuildAt, Integer sourceCount, Integer fileCount) {
    }

    public record KnowledgeFeatureView(Boolean implemented, Boolean enabled, String mode) {
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

    public record KnowledgeSearchMatchView(
            String sourceId,
            String displayName,
            String relativePath,
            Integer lineStart,
            Integer lineEnd,
            String snippet,
            String matchType,
            Double score
    ) {
    }
}
