package com.sitionix.forgeagent.domain.model;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Safe, invocation-local MCP metadata; no credential or runtime bearer belongs here. */
public record McpExecutionSelection(List<Entry> entries, List<Diagnostic> diagnostics) {
    public McpExecutionSelection {
        entries = List.copyOf(entries);
        diagnostics = List.copyOf(diagnostics);
    }

    public record Entry(String alias, UUID connectionId, String displayName, Set<McpAllowedTool> tools) {
        public Entry {
            if (alias == null || alias.isBlank() || connectionId == null || displayName == null || tools == null)
                throw new IllegalArgumentException("Invalid MCP execution entry");
            tools = Set.copyOf(tools);
        }
    }

    public enum DiagnosticCode {
        CONNECTION_UNAVAILABLE,
        MCP_CONNECTION_UNAVAILABLE,
        MCP_TOOL_UNAVAILABLE
    }

    public record Diagnostic(UUID connectionId, DiagnosticCode code) {
        public Diagnostic {
            if (connectionId == null || code == null)
                throw new IllegalArgumentException("Invalid MCP execution diagnostic");
        }
    }
}
