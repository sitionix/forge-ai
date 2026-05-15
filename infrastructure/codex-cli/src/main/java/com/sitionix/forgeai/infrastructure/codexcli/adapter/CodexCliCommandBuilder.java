package com.sitionix.forgeai.infrastructure.codexcli.adapter;

import org.springframework.stereotype.Component;

@Component
public class CodexCliCommandBuilder {

    public String build(final String prompt) {
        final String workspaceRoot = this.resolveWorkspaceRoot();
        return "cd " + this.shellQuote(workspaceRoot) + "; "
                + "echo '[forge-ai] cwd='$(pwd); "
                + "echo '[forge-ai] starting interactive codex'; "
                + "exec codex --no-alt-screen -C " + this.shellQuote(workspaceRoot) + " " + this.shellQuote(prompt);
    }

    private String resolveWorkspaceRoot() {
        final String envWorkspaceRoot = System.getenv("WORKSPACE_ROOT");
        if (envWorkspaceRoot != null && !envWorkspaceRoot.isBlank()) {
            return envWorkspaceRoot;
        }
        return System.getProperty("user.dir", ".");
    }

    private String shellQuote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
