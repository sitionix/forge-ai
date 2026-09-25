package com.sitionix.forgeai.api.mcp;

import com.sitionix.forgeai.domain.model.mcp.McpProbeReport;
import java.util.List;

public record McpProbeResponse(String protocolVersion, List<Tool> tools) {
    public static McpProbeResponse from(McpProbeReport report) {
        return new McpProbeResponse(report.protocolVersion(), report.tools().stream()
                .map(tool -> new Tool(tool.name(), tool.description(), tool.schemaFingerprint())).toList());
    }
    public record Tool(String name, String description, String schemaFingerprint) {}
}
