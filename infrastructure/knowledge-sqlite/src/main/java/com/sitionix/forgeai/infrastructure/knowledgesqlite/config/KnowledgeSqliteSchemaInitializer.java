package com.sitionix.forgeai.infrastructure.knowledgesqlite.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "forge.ai.infrastructure.knowledge.mode", havingValue = "sqlite")
public class KnowledgeSqliteSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    void initialize() throws Exception {
        final String schema = new ClassPathResource("db/migration/V1__create_knowledge_inventory_tables.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        for (final String statement : schema.split(";")) {
            if (!statement.isBlank()) {
                this.jdbcTemplate.execute(statement);
            }
        }
    }
}
