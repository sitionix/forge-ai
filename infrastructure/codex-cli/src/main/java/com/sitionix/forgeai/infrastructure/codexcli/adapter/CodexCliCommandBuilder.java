package com.sitionix.forgeai.infrastructure.codexcli.adapter;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.stereotype.Component;

@Component
public class CodexCliCommandBuilder {

    public String buildFromPromptFile(final String promptFilePath) {
        final String workspaceRoot = this.resolveWorkspaceRoot();
        final String scriptPath = this.resolveScriptPath(workspaceRoot);
        return "bash "
                + this.shellQuote(scriptPath)
                + " " + this.shellQuote(workspaceRoot)
                + " " + this.shellQuote(promptFilePath);
    }

    private String resolveWorkspaceRoot() {
        final String envWorkspaceRoot = System.getenv("WORKSPACE_ROOT");
        if (envWorkspaceRoot != null && !envWorkspaceRoot.isBlank()) {
            return this.normalizeWorkspaceRoot(envWorkspaceRoot);
        }
        return this.normalizeWorkspaceRoot(System.getProperty("user.dir", "."));
    }

    String normalizeWorkspaceRoot(final String rawWorkspaceRoot) {
        final Path normalizedPath = Paths.get(rawWorkspaceRoot).toAbsolutePath().normalize();
        return normalizedPath.toString();
    }

    String resolveScriptPath(final String workspaceRoot) {
        Path current = Paths.get(workspaceRoot).toAbsolutePath().normalize();
        while (current != null) {
            final Path repoLevelPath = current.resolve("forge-ai/infrastructure/codex-cli/src/main/resources/scripts/run-codex-with-prompt-file.sh");
            if (repoLevelPath.toFile().exists()) {
                return repoLevelPath.toString();
            }

            final Path moduleLevelPath = current.resolve("infrastructure/codex-cli/src/main/resources/scripts/run-codex-with-prompt-file.sh");
            if (moduleLevelPath.toFile().exists()) {
                return moduleLevelPath.toString();
            }

            current = current.getParent();
        }

        throw new IllegalStateException("Codex runner script not found for workspaceRoot: " + workspaceRoot);
    }

    private String shellQuote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
