package com.sitionix.forgeai.infrastructure.knowledgesqlite.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "forge.ai.infrastructure.knowledge.mode", havingValue = "sqlite")
public class KnowledgeSqliteSchemaInitializer {

    private final DataSource dataSource;

    @PostConstruct
    void initialize() throws Exception {
        final String schema = new ClassPathResource("db/migration/V1__create_knowledge_inventory_tables.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        try (Connection connection = this.dataSource.getConnection();
             Statement sql = connection.createStatement()) {
            for (final String statement : schema.split(";")) {
                if (!statement.isBlank()) {
                    sql.execute(statement);
                }
            }
            this.addColumnIfMissing(sql, "inventory_builds", "skipped_reasons_json", "TEXT");
            this.addColumnIfMissing(sql, "files", "line_count", "INTEGER NOT NULL DEFAULT 0");
            this.addColumnIfMissing(sql, "files", "decode_policy", "TEXT NOT NULL DEFAULT 'utf-8:replace'");
            this.addColumnIfMissing(sql, "files", "language", "TEXT");
            this.addColumnIfMissing(sql, "files", "flow_domain", "TEXT");
        }
    }

    private void addColumnIfMissing(final Statement sql,
                                    final String table,
                                    final String column,
                                    final String declaration) throws Exception {
        try (var columns = sql.executeQuery("PRAGMA table_info(%s)".formatted(table))) {
            while (columns.next()) {
                if (column.equals(columns.getString("name"))) {
                    return;
                }
            }
        }
        sql.execute("ALTER TABLE %s ADD COLUMN %s %s".formatted(table, column, declaration));
    }
}
