package com.sitionix.forgeai.infrastructure.knowledgesqlite.repository;

import com.sitionix.forgeai.infrastructure.knowledgesqlite.entity.KnowledgeFileEntity;
import com.sitionix.forgeai.infrastructure.knowledgesqlite.entity.KnowledgeInventoryBuildEntity;
import com.sitionix.forgeai.infrastructure.knowledgesqlite.entity.KnowledgeSourceEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = "forge.ai.infrastructure.knowledge.mode", havingValue = "sqlite")
public class KnowledgeSqliteRepository {

    private final DataSource dataSource;

    public KnowledgeInventoryBuildEntity replaceInventory(final List<KnowledgeSourceEntity> sources,
                                                          final List<KnowledgeFileEntity> files,
                                                          final int skipped,
                                                          final String startedAt,
                                                          final String completedAt) {
        try (Connection connection = this.dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                this.update(connection, "DELETE FROM files");
                this.update(connection, "DELETE FROM sources");
                for (KnowledgeSourceEntity source : sources) {
                    this.insertSource(connection, source);
                }
                for (KnowledgeFileEntity file : files) {
                    this.insertFile(connection, file);
                }
                this.update(connection, """
                                INSERT INTO inventory_builds(started_at, completed_at, status, source_count, file_count, skipped_count, skipped_reasons_json, error_message)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                        startedAt,
                        completedAt,
                        "COMPLETED",
                        sources.size(),
                        files.size(),
                        skipped,
                        "{\"total\":%d,\"byReason\":{}}".formatted(skipped),
                        null
                );
                final KnowledgeInventoryBuildEntity build = this.latestBuild(connection)
                        .orElseThrow(() -> new IllegalStateException("Knowledge inventory build was not persisted"));
                connection.commit();
                return build;
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to replace Knowledge SQLite inventory", exception);
        }
    }

    public Optional<KnowledgeInventoryBuildEntity> latestBuild() {
        try (Connection connection = this.dataSource.getConnection()) {
            return this.latestBuild(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to read Knowledge SQLite inventory build", exception);
        }
    }

    public List<KnowledgeSourceEntity> sources() {
        return this.query("""
                        SELECT source_id, display_name, group_name, path, root_exists, tags_json, metadata_json, last_seen_at
                        FROM sources
                        ORDER BY source_id
                        """,
                rs -> new KnowledgeSourceEntity(
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
        return this.query("""
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
        return this.query("SELECT COUNT(*) AS count FROM files f %s".formatted(filter.where()),
                rs -> rs.getInt("count"),
                filter.params().toArray()).stream().findFirst().orElse(0);
    }

    public List<KnowledgeFileEntity> contextFiles(final String query,
                                                  final List<String> sourceIds,
                                                  final List<String> groups,
                                                  final int maxItems) {
        final KnowledgeSqlFilter filter = KnowledgeSqlFilter.context(query, sourceIds, groups);
        final List<Object> params = new ArrayList<>(filter.params());
        params.add(maxItems);
        return this.query("""
                        SELECT f.*, s.display_name, s.group_name, s.tags_json, s.metadata_json
                        FROM files f
                        JOIN sources s ON s.source_id = f.source_id
                        %s
                        ORDER BY f.source_id, f.relative_path
                        LIMIT ?
                        """.formatted(filter.where()), fileMapper(), params.toArray());
    }

    private SqlRowMapper<KnowledgeFileEntity> fileMapper() {
        return rs -> new KnowledgeFileEntity(
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

    private Optional<KnowledgeInventoryBuildEntity> latestBuild(final Connection connection) {
        return this.query(connection, """
                        SELECT id, started_at, completed_at, status, source_count, file_count, skipped_count, skipped_reasons_json, error_message
                        FROM inventory_builds
                        ORDER BY id DESC
                        LIMIT 1
                        """,
                rs -> new KnowledgeInventoryBuildEntity(
                        rs.getLong("id"),
                        rs.getString("started_at"),
                        rs.getString("completed_at"),
                        rs.getString("status"),
                        rs.getInt("source_count"),
                        rs.getInt("file_count"),
                        rs.getInt("skipped_count"),
                        rs.getString("skipped_reasons_json"),
                        rs.getString("error_message")
                )).stream().findFirst();
    }

    private void insertSource(final Connection connection, final KnowledgeSourceEntity source) {
        this.update(connection, """
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

    private void insertFile(final Connection connection, final KnowledgeFileEntity file) {
        this.update(connection, """
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

    private <T> List<T> query(final String sql, final SqlRowMapper<T> mapper, final Object... params) {
        try (Connection connection = this.dataSource.getConnection()) {
            return this.query(connection, sql, mapper, params);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to query Knowledge SQLite inventory", exception);
        }
    }

    private <T> List<T> query(final Connection connection,
                              final String sql,
                              final SqlRowMapper<T> mapper,
                              final Object... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            this.bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                final List<T> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(mapper.map(rs));
                }
                return results;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to query Knowledge SQLite inventory", exception);
        }
    }

    private void update(final Connection connection, final String sql, final Object... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            this.bind(statement, params);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to update Knowledge SQLite inventory", exception);
        }
    }

    private void bind(final PreparedStatement statement, final Object... params) throws SQLException {
        for (int index = 0; index < params.length; index++) {
            statement.setObject(index + 1, params[index]);
        }
    }

    @FunctionalInterface
    private interface SqlRowMapper<T> {

        T map(ResultSet resultSet) throws SQLException;
    }
}
