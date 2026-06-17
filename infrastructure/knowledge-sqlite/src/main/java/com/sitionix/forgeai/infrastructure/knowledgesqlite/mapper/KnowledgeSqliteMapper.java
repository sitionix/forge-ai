package com.sitionix.forgeai.infrastructure.knowledgesqlite.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeFilesView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildResultView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryStatusView;
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
                new KnowledgeViews.KnowledgeInventoryRefreshView(false, null, "DISABLED", null, null, null, null, 0, 0),
                new KnowledgeViews.KnowledgeCoverageView(0, fileCount, null),
                new KnowledgeViews.KnowledgeFreshnessView("UNKNOWN", null, 0, 0, 0, 0),
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
                        file.getLastModified(),
                        file.getLineCount(),
                        file.getDecodePolicy()
                )).toList(),
                limit,
                offset,
                total
        );
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

    private record SkippedBreakdownJson(Integer total, Map<String, Integer> byReason) {
    }
}
