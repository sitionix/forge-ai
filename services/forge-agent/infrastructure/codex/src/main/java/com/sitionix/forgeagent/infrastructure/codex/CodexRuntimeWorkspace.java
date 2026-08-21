package com.sitionix.forgeagent.infrastructure.codex;

import com.sitionix.forgeagent.application.runtime.ExecutionWorkspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
final class CodexRuntimeWorkspace {

    private final CodexAppServerProperties properties;

    ExecutionWorkspace routingWorkspace() {
        final String configured = this.properties.getRuntimeCwd();
        final Path path = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "forge-agent-codex-runtime").toAbsolutePath().normalize()
                : Path.of(configured.trim()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(path);
            return new ExecutionWorkspace(path, List.of(path));
        } catch (final IOException exception) {
            throw new CodexTransportException("Codex routing workspace is unavailable.", exception);
        }
    }
}
