package com.sitionix.forgeai.domain.model.mcp;

import java.util.List;

public record McpProbeReport(String protocolVersion, List<Tool> tools) {
    public McpProbeReport {
        if (protocolVersion == null || protocolVersion.isBlank() || tools == null)
            throw new IllegalArgumentException("Invalid MCP probe report");
        tools = List.copyOf(tools);
    }
    public record Tool(String name, String description, String schemaFingerprint) {
        public Tool {
            if (name == null || name.isBlank() || schemaFingerprint == null || schemaFingerprint.isBlank())
                throw new IllegalArgumentException("Invalid MCP tool summary");
        }
    }
}
