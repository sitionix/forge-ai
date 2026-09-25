package com.sitionix.forgeagent.domain.model;

public record McpToolSummary(String name, String description, String schemaFingerprint) {
    public McpToolSummary {
        if (name == null || name.isBlank() || schemaFingerprint == null || schemaFingerprint.isBlank())
            throw new IllegalArgumentException("Invalid MCP tool");
    }
}
