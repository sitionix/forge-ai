package com.sitionix.forgeai.infrastructure.knowledgesqlite.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextMetadataView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextItemView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextSourceView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeFilesView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildResultView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeSearchResultView;
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
                        build == null ? null : build.getCompletedAt(),
                        build == null ? 0 : build.getSourceCount(),
                        fileCount
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
            return new KnowledgeInventoryBuildResultView("EMPTY", 0, 0, 0, null, null);
        }
        return new KnowledgeInventoryBuildResultView(
                build.getStatus(),
                build.getSourceCount(),
                build.getFileCount(),
                build.getSkippedCount(),
                build.getStartedAt(),
                build.getCompletedAt()
        );
    }

    public KnowledgeInventoryStatusView inventoryStatus(final KnowledgeInventoryBuildEntity build) {
        if (build == null) {
            return new KnowledgeInventoryStatusView("EMPTY", null, 0, 0, 0);
        }
        return new KnowledgeInventoryStatusView(
                "COMPLETED".equals(build.getStatus()) ? "READY" : build.getStatus(),
                build.getCompletedAt(),
                build.getSourceCount(),
                build.getFileCount(),
                build.getSkippedCount()
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
        final String content = includeContent ? """
                SQLite indexed file
                sourceId: %s
                path: %s
                hash: %s""".formatted(file.getSourceId(), file.getRelativePath(), file.getContentHash()) : null;
        return new KnowledgeContextItemView(
                file.getSourceId(),
                file.getDisplayName(),
                file.getGroup(),
                file.getRelativePath(),
                1,
                40,
                content,
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
}
