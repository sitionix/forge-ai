package com.sitionix.forgeagent.domain.model;

public record McpAllowedTool(String name, String schemaFingerprint) {
    public McpAllowedTool {
        if (name == null || name.isBlank() || schemaFingerprint == null || schemaFingerprint.isBlank())
            throw new IllegalArgumentException("Invalid tool approval");
    }
}
