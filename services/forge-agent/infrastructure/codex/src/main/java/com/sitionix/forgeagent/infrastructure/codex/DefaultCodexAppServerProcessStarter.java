package com.sitionix.forgeagent.infrastructure.codex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
final class DefaultCodexAppServerProcessStarter implements CodexAppServerProcessStarter {

    private final CodexAppServerProperties properties;

    DefaultCodexAppServerProcessStarter(final CodexAppServerProperties properties) {
        this.properties = properties;
    }

    @Override
    public StartedCodexAppServer start() {
        final List<String> command = List.copyOf(this.properties.getCommand());
        try {
            final ProcessBuilder builder = new ProcessBuilder(command);
            if (this.properties.getRuntimeCwd() != null && !this.properties.getRuntimeCwd().isBlank()) {
                builder.directory(Path.of(this.properties.getRuntimeCwd()).toFile());
            }
            final Process process = builder.start();
            return new StartedCodexAppServer(process, command, this.detectCodexVersion(command), Instant.now());
        } catch (final IOException e) {
            throw new CodexTransportException("Failed to start Codex app-server", e);
        }
    }

    private String detectCodexVersion(final List<String> command) {
        if (command.isEmpty()) {
            return "unknown";
        }
        try {
            final ProcessBuilder builder = new ProcessBuilder(command.getFirst(), "--version");
            if (this.properties.getRuntimeCwd() != null && !this.properties.getRuntimeCwd().isBlank()) {
                builder.directory(Path.of(this.properties.getRuntimeCwd()).toFile());
            }
            final Process versionProcess = builder.start();
            final byte[] stdout = versionProcess.getInputStream().readNBytes(4096);
            final byte[] stderr = versionProcess.getErrorStream().readNBytes(4096);
            versionProcess.waitFor();
            final String text = new String(stdout, StandardCharsets.UTF_8).trim();
            if (!text.isBlank()) {
                return text;
            }
            final String error = new String(stderr, StandardCharsets.UTF_8).trim();
            return error.isBlank() ? "unknown" : error;
        } catch (final Exception ignored) {
            return "unknown";
        }
    }
}
