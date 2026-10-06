package com.sitionix.forgeagent.infrastructure.codex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import com.sitionix.forgeagent.domain.model.McpRuntimeLaunchGrants;
import com.sitionix.forgeagent.infrastructure.local.runtime.RuntimeProcessLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
final class DefaultCodexAppServerProcessStarter implements CodexAppServerProcessStarter {

    private final CodexAppServerProperties properties;
    private final RuntimeProcessLauncher launcher;

    @Autowired
    DefaultCodexAppServerProcessStarter(CodexAppServerProperties properties, RuntimeProcessLauncher launcher) {
        this.properties = properties;
        this.launcher = launcher;
    }

    @Override
    public StartedCodexAppServer start(final Path workingDirectory) {
        return this.startProcess(workingDirectory, null);
    }

    @Override
    public StartedCodexAppServer start(final Path workingDirectory, final McpRuntimeLaunchGrants grants) {
        if (grants == null) throw new CodexTransportException("Codex MCP launch grants are unavailable");
        return this.startProcess(workingDirectory, grants);
    }

    private StartedCodexAppServer startProcess(final Path workingDirectory, final McpRuntimeLaunchGrants grants) {
        final List<String> command = List.copyOf(this.properties.getCommand());
        final Path launchDirectory = workingDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(launchDirectory)) {
            throw new CodexTransportException("Codex app-server working directory is unavailable");
        }
        try {
            return new StartedCodexAppServer(grants == null
                    ? this.launcher.startCodex(launchDirectory)
                    : this.launcher.startCodex(launchDirectory, grants.environment()),
                    List.of("codex", "app-server", "--stdio"), Instant.now());
        } catch (final IOException | IllegalStateException e) {
            throw new CodexTransportException("Failed to start Codex app-server", e);
        }
    }
}
