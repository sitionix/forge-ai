package com.sitionix.forgeai.infrastructure.knowledgesqlite.config;

import org.sqlite.SQLiteDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
@EnableConfigurationProperties(KnowledgeSqliteProperties.class)
@ConditionalOnProperty(name = "forge.ai.infrastructure.knowledge.mode", havingValue = "sqlite")
public class KnowledgeSqliteConfiguration {

    @Bean
    @ConditionalOnMissingBean(DataSource.class)
    DataSource knowledgeSqliteDataSource(final KnowledgeSqliteProperties properties) throws Exception {
        final Path path = Path.of(properties.getPath());
        final Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        final SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + properties.getPath());
        return dataSource;
    }
}
