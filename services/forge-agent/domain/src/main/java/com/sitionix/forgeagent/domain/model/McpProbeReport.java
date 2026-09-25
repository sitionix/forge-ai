package com.sitionix.forgeagent.domain.model;

import java.util.List;

public record McpProbeReport(String protocolVersion, List<McpToolSummary> tools) {
    public McpProbeReport {
        if (protocolVersion == null || protocolVersion.isBlank() || tools == null || tools.stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("Invalid MCP probe result");
        tools = List.copyOf(tools);
    }
}
