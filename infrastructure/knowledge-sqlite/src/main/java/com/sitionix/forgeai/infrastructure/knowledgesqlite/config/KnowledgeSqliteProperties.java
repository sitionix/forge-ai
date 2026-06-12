package com.sitionix.forgeai.infrastructure.knowledgesqlite.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "forge.ai.infrastructure.knowledge.sqlite")
public class KnowledgeSqliteProperties {

    private String path = "infrastructure/knowledge/var/knowledge.sqlite";
    private String workspaceRoot = System.getProperty("user.dir");
    private Integer maxFileSizeBytes = 500000;
    private List<String> includePatterns = List.of(
            "**/*.java", "**/*.kt", "**/*.ts", "**/*.tsx", "**/*.js", "**/*.md",
            "**/*.yaml", "**/*.yml", "**/*.json", "**/*.xml", "**/pom.xml", "pom.xml", "**/README*", "README*"
    );
    private List<String> excludedDirNames = List.of(
            ".git", "target", "build", "dist", "node_modules", ".venv", "var", "logs"
    );
}
