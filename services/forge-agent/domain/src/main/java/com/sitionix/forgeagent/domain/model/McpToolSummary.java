package com.sitionix.forgeagent.domain.model;

public record McpToolSummary(String name, String description, String schemaFingerprint,
                             java.util.Map<String,Object> inputSchema,
                             java.util.Map<String,Object> outputSchema) {
    public McpToolSummary(String name, String description, String schemaFingerprint) {
        this(name, description, schemaFingerprint, null, null);
    }
    public McpToolSummary {
        if (name == null || name.isBlank() || schemaFingerprint == null || schemaFingerprint.isBlank())
            throw new IllegalArgumentException("Invalid MCP tool");
    }
}
