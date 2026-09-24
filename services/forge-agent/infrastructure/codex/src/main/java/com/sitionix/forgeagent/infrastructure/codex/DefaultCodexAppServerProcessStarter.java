package com.sitionix.forgeagent.infrastructure.codex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import com.sitionix.forgeagent.infrastructure.local.runtime.RuntimeProcessLauncher;
import com.sitionix.forgeagent.infrastructure.local.runtime.RuntimeBoundaryProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
final class DefaultCodexAppServerProcessStarter implements CodexAppServerProcessStarter {

    private final CodexAppServerProperties properties;
    private final RuntimeProcessLauncher launcher;

    DefaultCodexAppServerProcessStarter(CodexAppServerProperties properties) {
        this(properties, new RuntimeProcessLauncher(RuntimeBoundaryProperties.disabled()));
    }

    @Autowired
    DefaultCodexAppServerProcessStarter(CodexAppServerProperties properties, RuntimeProcessLauncher launcher) {
        this.properties = properties;
        this.launcher = launcher;
    }

    @Override
    public StartedCodexAppServer start(final Path workingDirectory) {
        final List<String> command = List.copyOf(this.properties.getCommand());
        final Path launchDirectory = workingDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(launchDirectory)) {
            throw new CodexTransportException("Codex app-server working directory is unavailable");
        }
        try {
            if (this.launcher.enabled()) {
                return new StartedCodexAppServer(this.launcher.startCodex(launchDirectory),
                    List.of("codex", "app-server", "--stdio"), Instant.now());
            }
            final ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(launchDirectory.toFile());
            final Process process = builder.start();
            return new StartedCodexAppServer(process, command, Instant.now());
        } catch (final IOException | IllegalStateException e) {
            throw new CodexTransportException("Failed to start Codex app-server", e);
        }
    }
}
