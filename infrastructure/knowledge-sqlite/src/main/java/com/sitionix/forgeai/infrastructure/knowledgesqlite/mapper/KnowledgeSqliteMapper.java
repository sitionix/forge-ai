package com.sitionix.forgeai.infrastructure.knowledgesqlite.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextItemView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextSourceView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeFilesView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildResultView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeSearchResultView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeSourcesView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeViews;
import com.sitionix.forgeai.infrastructure.knowledgesqlite.entity.KnowledgeFileEntity;
import com.sitionix.forgeai.infrastructure.knowledgesqlite.entity.KnowledgeInventoryBuildEntity;
import com.sitionix.forgeai.infrastructure.knowledgesqlite.entity.KnowledgeSourceEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "forge.ai.infrastructure.knowledge.mode", havingValue = "sqlite")
public class KnowledgeSqliteMapper {

    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public KnowledgeStatusView status(final KnowledgeInventoryBuildEntity build, final int fileCount) {
        final Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("implemented", true);
        inventory.put("lastBuildAt", build == null ? null : build.getCompletedAt());
        inventory.put("fileCount", fileCount);
        return new KnowledgeStatusView(
                "UP",
                "knowledge",
                new KnowledgeViews.KnowledgeCatalogView(true, "sqlite"),
                inventory,
                Map.of("implemented", true, "mode", "keyword"),
                Map.of("implemented", false),
                Map.of("implemented", false),
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
        final Map<String, Object> metadata = metadata(file.getMetadataJson());
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
                Map.of(
                        "tags", listMetadata(metadata, "tags"),
                        "domainKeywords", listMetadata(metadata, "domainKeywords"),
                        "ownsBusinessAreas", listMetadata(metadata, "ownsBusinessAreas")
                )
        );
    }

    public KnowledgeContextSourceView contextSource(final KnowledgeContextItemView item) {
        return new KnowledgeContextSourceView(item.sourceId(), item.displayName(), "Read from SQLite inventory");
    }

    private KnowledgeViews.KnowledgeSourceView source(final KnowledgeSourceEntity source) {
        final Map<String, Object> metadata = metadata(source.getMetadataJson());
        return new KnowledgeViews.KnowledgeSourceView(
                source.getSourceId(),
                source.getDisplayName(),
                source.getGroup(),
                source.getPath(),
                source.getRootExists(),
                listMetadata(metadata, "tags"),
                listMetadata(metadata, "domainKeywords"),
                listMetadata(metadata, "ownsBusinessAreas"),
                List.of()
        );
    }

    private Map<String, Object> metadata(final String json) {
        try {
            return this.objectMapper.readValue(json, METADATA_TYPE);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private List<String> listMetadata(final Map<String, Object> metadata, final String key) {
        final Object value = metadata.get(key);
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }
}
