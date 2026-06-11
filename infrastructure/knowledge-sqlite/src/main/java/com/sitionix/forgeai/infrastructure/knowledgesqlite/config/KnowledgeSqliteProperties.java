package com.sitionix.forgeai.infrastructure.knowledgesqlite.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "forge.ai.infrastructure.knowledge.sqlite")
public class KnowledgeSqliteProperties {

    private String path = "infrastructure/knowledge/var/knowledge.sqlite";
}
