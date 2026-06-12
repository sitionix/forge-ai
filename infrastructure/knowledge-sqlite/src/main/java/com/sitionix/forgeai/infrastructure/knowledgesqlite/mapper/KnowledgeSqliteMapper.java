package com.sitionix.forgeai.infrastructure.knowledgesqlite.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextMetadataView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextItemView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextSourceView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeFilesView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildResultView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeSearchResultView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeSkippedBreakdownView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeSourcesView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeViews;
import com.sitionix.forgeai.infrastructure.knowledgesqlite.adapter.KnowledgeSourceMetadata;
import com.sitionix.forgeai.infrastructure.knowledgesqlite.entity.KnowledgeFileEntity;
import com.sitionix.forgeai.infrastructure.knowledgesqlite.entity.KnowledgeInventoryBuildEntity;
import com.sitionix.forgeai.infrastructure.knowledgesqlite.entity.KnowledgeSourceEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "forge.ai.infrastructure.knowledge.mode", havingValue = "sqlite")
public class KnowledgeSqliteMapper {

    private final ObjectMapper objectMapper;

    public KnowledgeStatusView status(final KnowledgeInventoryBuildEntity build, final int fileCount) {
        return new KnowledgeStatusView(
                "UP",
                "knowledge",
                new KnowledgeViews.KnowledgeCatalogView(true, "sqlite"),
                new KnowledgeViews.KnowledgeInventorySummaryView(
                        true,
                        build == null ? "EMPTY" : "COMPLETED".equals(build.getStatus()) ? "READY" : build.getStatus(),
                        build == null ? null : build.getCompletedAt(),
                        build == null ? 0 : build.getSourceCount(),
                        fileCount,
                        build == null ? 0 : build.getSkippedCount(),
                        this.skippedBreakdown(build)
                ),
                new KnowledgeViews.KnowledgeFeatureView(true, true, "keyword"),
                new KnowledgeViews.KnowledgeFeatureView(false, false, null),
                new KnowledgeViews.KnowledgeFeatureView(false, false, null),
                null
        );
    }

    public KnowledgeSourcesView sources(final List<KnowledgeSourceEntity> sources) {
        return new KnowledgeSourcesView(
                new KnowledgeViews.KnowledgeCatalogView(true, "sqlite"),
                sources.stream().map(this::source).toList(),
                List.of(),
                null
        );
    }

    public KnowledgeInventoryBuildResultView buildResult(final KnowledgeInventoryBuildEntity build) {
        if (build == null) {
            return new KnowledgeInventoryBuildResultView("EMPTY", 0, 0, 0, this.emptySkippedBreakdown(0), null, null);
        }
        return new KnowledgeInventoryBuildResultView(
                build.getStatus(),
                build.getSourceCount(),
                build.getFileCount(),
                build.getSkippedCount(),
                this.skippedBreakdown(build),
                build.getStartedAt(),
                build.getCompletedAt()
        );
    }

    public KnowledgeInventoryStatusView inventoryStatus(final KnowledgeInventoryBuildEntity build) {
        if (build == null) {
            return new KnowledgeInventoryStatusView("EMPTY", null, 0, 0, 0, this.emptySkippedBreakdown(0));
        }
        return new KnowledgeInventoryStatusView(
                "COMPLETED".equals(build.getStatus()) ? "READY" : build.getStatus(),
                build.getCompletedAt(),
                build.getSourceCount(),
                build.getFileCount(),
                build.getSkippedCount(),
                this.skippedBreakdown(build)
        );
    }

    public KnowledgeFilesView files(final List<KnowledgeFileEntity> files,
                                    final int limit,
                                    final int offset,
                                    final int total) {
        return new KnowledgeFilesView(
                files.stream().map(file -> new KnowledgeViews.KnowledgeFileView(
                        file.getSourceId(),
                        file.getSourcePath(),
                        file.getRelativePath(),
                        file.getExtension(),
                        file.getSizeBytes(),
                        file.getContentHash(),
                        file.getLastModified()
                )).toList(),
                limit,
                offset,
                total
        );
    }

    public KnowledgeSearchResultView search(final String query, final List<KnowledgeFileEntity> files) {
        return new KnowledgeSearchResultView(
                query,
                files.stream().map(file -> new KnowledgeViews.KnowledgeSearchMatchView(
                        file.getSourceId(),
                        file.getDisplayName(),
                        file.getRelativePath(),
                        1,
                        1,
                        file.getRelativePath(),
                        "path",
                        1.0
                )).toList(),
                null
        );
    }

    public KnowledgeContextItemView contextItem(final KnowledgeFileEntity file, final boolean includeContent) {
        final KnowledgeSourceMetadata metadata = metadata(file.getMetadataJson());
        final ContextSnippet snippet = this.snippet(file, metadata, includeContent);
        return new KnowledgeContextItemView(
                file.getSourceId(),
                file.getDisplayName(),
                file.getGroup(),
                file.getRelativePath(),
                snippet.lineStart(),
                snippet.lineEnd(),
                snippet.content(),
                "path",
                "Matched query against SQLite inventory metadata",
                1.0,
                new KnowledgeContextMetadataView(
                        metadata.getTags(),
                        metadata.getDomainKeywords(),
                        metadata.getOwnsBusinessAreas()
                )
        );
    }

    public KnowledgeContextSourceView contextSource(final KnowledgeContextItemView item) {
        return new KnowledgeContextSourceView(item.sourceId(), item.displayName(), "Read from SQLite inventory");
    }

    private KnowledgeViews.KnowledgeSourceView source(final KnowledgeSourceEntity source) {
        final KnowledgeSourceMetadata metadata = metadata(source.getMetadataJson());
        return new KnowledgeViews.KnowledgeSourceView(
                source.getSourceId(),
                source.getDisplayName(),
                source.getGroup(),
                source.getPath(),
                source.getRootExists(),
                metadata.getTags(),
                metadata.getDomainKeywords(),
                metadata.getOwnsBusinessAreas(),
                List.of()
        );
    }

    private KnowledgeSourceMetadata metadata(final String json) {
        try {
            return this.objectMapper.readValue(json, KnowledgeSourceMetadata.class);
        } catch (Exception exception) {
            return KnowledgeSourceMetadata.builder()
                    .tags(List.of())
                    .domainKeywords(List.of())
                    .ownsBusinessAreas(List.of())
                    .build();
        }
    }

    private KnowledgeSkippedBreakdownView skippedBreakdown(final KnowledgeInventoryBuildEntity build) {
        if (build == null) {
            return this.emptySkippedBreakdown(0);
        }
        if (build.getSkippedReasonsJson() == null || build.getSkippedReasonsJson().isBlank()) {
            return this.emptySkippedBreakdown(build.getSkippedCount());
        }
        try {
            final SkippedBreakdownJson json = this.objectMapper.readValue(build.getSkippedReasonsJson(), SkippedBreakdownJson.class);
            final Map<String, Integer> byReason = json.byReason() == null ? Map.of() : json.byReason();
            final Integer total = json.total() == null ? byReason.values().stream().mapToInt(Integer::intValue).sum() : json.total();
            return new KnowledgeSkippedBreakdownView(total, byReason);
        } catch (Exception exception) {
            return this.emptySkippedBreakdown(build.getSkippedCount());
        }
    }

    private KnowledgeSkippedBreakdownView emptySkippedBreakdown(final Integer skippedCount) {
        return new KnowledgeSkippedBreakdownView(skippedCount == null ? 0 : skippedCount, Map.of());
    }

    private ContextSnippet snippet(final KnowledgeFileEntity file,
                                   final KnowledgeSourceMetadata metadata,
                                   final boolean includeContent) {
        if (!includeContent) {
            return new ContextSnippet(1, 40, null);
        }
        final List<String> lines = this.readLines(file, metadata);
        if (!lines.isEmpty()) {
            final int lineEnd = Math.min(lines.size(), 40);
            return new ContextSnippet(1, lineEnd, String.join("\n", lines.subList(0, lineEnd)));
        }
        return new ContextSnippet(1, 40, """
                SQLite indexed file
                sourceId: %s
                path: %s
                hash: %s""".formatted(file.getSourceId(), file.getRelativePath(), file.getContentHash()));
    }

    private List<String> readLines(final KnowledgeFileEntity file, final KnowledgeSourceMetadata metadata) {
        final String root = metadata.getAbsoluteRoot() == null || metadata.getAbsoluteRoot().isBlank()
                ? file.getSourcePath()
                : metadata.getAbsoluteRoot();
        if (root == null || root.isBlank() || file.getAbsolutePath() == null || file.getAbsolutePath().isBlank()) {
            return List.of();
        }
        try {
            final Path rootPath = Path.of(root).toRealPath().normalize();
            final Path filePath = Path.of(file.getAbsolutePath()).toRealPath().normalize();
            if (!filePath.startsWith(rootPath)) {
                return List.of();
            }
            return Files.readAllLines(filePath, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return List.of();
        }
    }

    private record ContextSnippet(Integer lineStart, Integer lineEnd, String content) {
    }

    private record SkippedBreakdownJson(Integer total, Map<String, Integer> byReason) {
    }
}
