package com.sitionix.forgeai.infrastructure.knowledgesqlite.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "forge.ai.infrastructure.knowledge.sqlite")
public class KnowledgeSqliteProperties {

    private String path = "${FORGE_RUNTIME_DIR:var}/knowledge/knowledge.sqlite";
    private String workspaceRoot = System.getProperty("user.dir");
    private Integer maxFileSizeBytes = 500000;
    private List<String> includePatterns = List.of();
    private List<String> excludedDirNames = List.of();
    private List<String> rootMarkerPaths = List.of();
}
