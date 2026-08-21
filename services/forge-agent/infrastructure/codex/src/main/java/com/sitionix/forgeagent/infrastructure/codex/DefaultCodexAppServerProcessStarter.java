package com.sitionix.forgeagent.infrastructure.codex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
final class DefaultCodexAppServerProcessStarter implements CodexAppServerProcessStarter {

    private final CodexAppServerProperties properties;

    @Override
    public StartedCodexAppServer start(final Path workingDirectory) {
        final List<String> command = List.copyOf(this.properties.getCommand());
        try {
            final ProcessBuilder builder = new ProcessBuilder(command);
            final Path launchDirectory = workingDirectory.toAbsolutePath().normalize();
            Files.createDirectories(launchDirectory);
            builder.directory(launchDirectory.toFile());
            final Process process = builder.start();
            return new StartedCodexAppServer(process, command, Instant.now());
        } catch (final IOException e) {
            throw new CodexTransportException("Failed to start Codex app-server", e);
        }
    }
}
