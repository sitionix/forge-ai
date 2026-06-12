package com.sitionix.forgeai.infrastructure.knowledgesqlite.adapter;

import com.sitionix.forgeai.infrastructure.knowledgesqlite.config.KnowledgeSqliteProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "forge.ai.infrastructure.knowledge.mode", havingValue = "sqlite")
public class KnowledgeSourceRootResolver {

    private final KnowledgeSqliteProperties properties;

    public Path resolve(final String configuredPath) {
        final Path rawPath = Path.of(configuredPath == null ? "" : configuredPath).normalize();
        if (rawPath.isAbsolute()) {
            return rawPath.toAbsolutePath().normalize();
        }
        final Path repoRoot = this.repoRoot();
        if (repoRoot.getFileName() != null && repoRoot.getFileName().toString().equals(rawPath.toString())) {
            return repoRoot;
        }
        final Path workspaceRoot = repoRoot.getParent() == null ? repoRoot : repoRoot.getParent();
        final Path workspaceSibling = workspaceRoot.resolve(rawPath).toAbsolutePath().normalize();
        if (Files.exists(workspaceSibling)) {
            return workspaceSibling;
        }
        return repoRoot.resolve(rawPath).toAbsolutePath().normalize();
    }

    private Path repoRoot() {
        Path current = Path.of(this.properties.getWorkspaceRoot()).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve(".git")) || Files.exists(current.resolve("boot/src/main/resources/services.yaml"))) {
                return current;
            }
            current = current.getParent();
        }
        return Path.of(this.properties.getWorkspaceRoot()).toAbsolutePath().normalize();
    }
}
