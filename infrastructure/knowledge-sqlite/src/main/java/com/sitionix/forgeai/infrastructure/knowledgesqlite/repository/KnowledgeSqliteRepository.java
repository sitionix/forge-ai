package com.sitionix.forgeai.infrastructure.knowledgesqlite.repository;

import com.sitionix.forgeai.infrastructure.knowledgesqlite.entity.KnowledgeFileEntity;
import com.sitionix.forgeai.infrastructure.knowledgesqlite.entity.KnowledgeInventoryBuildEntity;
import com.sitionix.forgeai.infrastructure.knowledgesqlite.entity.KnowledgeSourceEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = "forge.ai.infrastructure.knowledge.mode", havingValue = "sqlite")
public class KnowledgeSqliteRepository {

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public KnowledgeInventoryBuildEntity replaceInventory(final List<KnowledgeSourceEntity> sources,
                                                          final List<KnowledgeFileEntity> files,
                                                          final int skipped,
                                                          final String startedAt,
                                                          final String completedAt) {
        this.jdbcTemplate.update("DELETE FROM files");
        this.jdbcTemplate.update("DELETE FROM sources");
        sources.forEach(this::insertSource);
        files.forEach(this::insertFile);
        this.jdbcTemplate.update("""
                        INSERT INTO inventory_builds(started_at, completed_at, status, source_count, file_count, skipped_count, error_message)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                startedAt,
                completedAt,
                "COMPLETED",
                sources.size(),
                files.size(),
                skipped,
                null
        );
        return this.latestBuild().orElseThrow(() -> new IllegalStateException("Knowledge inventory build was not persisted"));
    }

    public Optional<KnowledgeInventoryBuildEntity> latestBuild() {
        final List<KnowledgeInventoryBuildEntity> builds = this.jdbcTemplate.query("""
                        SELECT id, started_at, completed_at, status, source_count, file_count, skipped_count, error_message
                        FROM inventory_builds
                        ORDER BY id DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> new KnowledgeInventoryBuildEntity(
                        rs.getLong("id"),
                        rs.getString("started_at"),
                        rs.getString("completed_at"),
                        rs.getString("status"),
                        rs.getInt("source_count"),
                        rs.getInt("file_count"),
                        rs.getInt("skipped_count"),
                        rs.getString("error_message")
                ));
        return builds.stream().findFirst();
    }

    public List<KnowledgeSourceEntity> sources() {
        return this.jdbcTemplate.query("""
                        SELECT source_id, display_name, group_name, path, root_exists, tags_json, metadata_json, last_seen_at
                        FROM sources
                        ORDER BY source_id
                        """,
                (rs, rowNum) -> new KnowledgeSourceEntity(
                        rs.getString("source_id"),
                        rs.getString("display_name"),
                        rs.getString("group_name"),
                        rs.getString("path"),
                        rs.getInt("root_exists") == 1,
                        rs.getString("tags_json"),
                        rs.getString("metadata_json"),
                        rs.getString("last_seen_at")
                ));
    }

    public List<KnowledgeFileEntity> files(final String sourceId,
                                           final String pathContains,
                                           final String extension,
                                           final int limit,
                                           final int offset) {
        final KnowledgeSqlFilter filter = KnowledgeSqlFilter.files(sourceId, pathContains, extension);
        final List<Object> params = new ArrayList<>(filter.params());
        params.add(limit);
        params.add(offset);
        return this.jdbcTemplate.query("""
                        SELECT f.*, s.display_name, s.group_name, s.tags_json, s.metadata_json
                        FROM files f
                        JOIN sources s ON s.source_id = f.source_id
                        %s
                        ORDER BY f.source_id, f.relative_path
                        LIMIT ? OFFSET ?
                        """.formatted(filter.where()), fileMapper(), params.toArray());
    }

    public int fileCount(final String sourceId, final String pathContains, final String extension) {
        final KnowledgeSqlFilter filter = KnowledgeSqlFilter.files(sourceId, pathContains, extension);
        return this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM files f %s".formatted(filter.where()),
                Integer.class,
                filter.params().toArray()
        );
    }

    public List<KnowledgeFileEntity> contextFiles(final String query,
                                                  final List<String> sourceIds,
                                                  final List<String> groups,
                                                  final int maxItems) {
        final KnowledgeSqlFilter filter = KnowledgeSqlFilter.context(query, sourceIds, groups);
        final List<Object> params = new ArrayList<>(filter.params());
        params.add(maxItems);
        return this.jdbcTemplate.query("""
                        SELECT f.*, s.display_name, s.group_name, s.tags_json, s.metadata_json
                        FROM files f
                        JOIN sources s ON s.source_id = f.source_id
                        %s
                        ORDER BY f.source_id, f.relative_path
                        LIMIT ?
                        """.formatted(filter.where()), fileMapper(), params.toArray());
    }

    private org.springframework.jdbc.core.RowMapper<KnowledgeFileEntity> fileMapper() {
        return (rs, rowNum) -> new KnowledgeFileEntity(
                rs.getLong("id"),
                rs.getString("source_id"),
                rs.getString("source_path"),
                rs.getString("absolute_path"),
                rs.getString("relative_path"),
                rs.getString("extension"),
                rs.getLong("size_bytes"),
                rs.getString("content_hash"),
                rs.getString("last_modified"),
                rs.getString("indexed_at"),
                rs.getString("display_name"),
                rs.getString("group_name"),
                rs.getString("tags_json"),
                rs.getString("metadata_json")
        );
    }

    private void insertSource(final KnowledgeSourceEntity source) {
        this.jdbcTemplate.update("""
                        INSERT INTO sources(source_id, display_name, group_name, path, root_exists, tags_json, metadata_json, last_seen_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                source.getSourceId(),
                source.getDisplayName(),
                source.getGroup(),
                source.getPath(),
                Boolean.TRUE.equals(source.getRootExists()) ? 1 : 0,
                source.getTagsJson(),
                source.getMetadataJson(),
                source.getLastSeenAt()
        );
    }

    private void insertFile(final KnowledgeFileEntity file) {
        this.jdbcTemplate.update("""
                        INSERT INTO files(source_id, source_path, absolute_path, relative_path, extension, size_bytes, content_hash, last_modified, indexed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                file.getSourceId(),
                file.getSourcePath(),
                file.getAbsolutePath(),
                file.getRelativePath(),
                file.getExtension(),
                file.getSizeBytes(),
                file.getContentHash(),
                file.getLastModified(),
                file.getIndexedAt()
        );
    }
}
