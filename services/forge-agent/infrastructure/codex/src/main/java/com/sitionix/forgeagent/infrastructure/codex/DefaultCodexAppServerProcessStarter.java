package com.sitionix.forgeagent.infrastructure.codex;

import java.io.IOException;
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
    public StartedCodexAppServer start() {
        final List<String> command = List.copyOf(this.properties.getCommand());
        try {
            final ProcessBuilder builder = new ProcessBuilder(command);
            if (this.properties.getRuntimeCwd() != null && !this.properties.getRuntimeCwd().isBlank()) {
                builder.directory(Path.of(this.properties.getRuntimeCwd()).toFile());
            }
            final Process process = builder.start();
            return new StartedCodexAppServer(process, command, Instant.now());
        } catch (final IOException e) {
            throw new CodexTransportException("Failed to start Codex app-server", e);
        }
    }
}
