package com.sitionix.forgeai.infrastructure.codexcli.adapter.appserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
            final Process process = new ProcessBuilder(command).start();
            return new StartedCodexAppServer(process, command, this.detectCodexVersion(command), Instant.now());
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to start Codex app-server command=" + String.join(" ", command), e);
        }
    }

    private String detectCodexVersion(final List<String> command) {
        if (command.isEmpty()) {
            return "unknown";
        }
        try {
            final Process versionProcess = new ProcessBuilder(command.getFirst(), "--version").start();
            final byte[] output = versionProcess.getInputStream().readAllBytes();
            final byte[] errors = versionProcess.getErrorStream().readAllBytes();
            versionProcess.waitFor();
            final String text = new String(output, StandardCharsets.UTF_8).trim();
            if (!text.isBlank()) {
                return text;
            }
            final String stderr = new String(errors, StandardCharsets.UTF_8).trim();
            return stderr.isBlank() ? "unknown" : stderr;
        } catch (final Exception e) {
            return "unknown";
        }
    }
}
