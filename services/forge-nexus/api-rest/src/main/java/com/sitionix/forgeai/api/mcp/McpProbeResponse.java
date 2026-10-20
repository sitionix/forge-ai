package com.sitionix.forgeai.api.mcp;

import com.sitionix.forgeai.domain.model.mcp.McpProbeReport;
import java.util.List;

public record McpProbeResponse(String protocolVersion, List<Tool> tools) {
    public static McpProbeResponse from(McpProbeReport report) {
        return new McpProbeResponse(report.protocolVersion(), report.tools().stream()
                .map(tool -> new Tool(tool.name(), tool.description(), tool.schemaFingerprint(), tool.inputSchema(), tool.outputSchema())).toList());
    }
    public record Tool(String name, String description, String schemaFingerprint, java.util.Map<String,Object> inputSchema, java.util.Map<String,Object> outputSchema) {
        public Tool(String name, String description, String schemaFingerprint) {
            this(name, description, schemaFingerprint, null, null);
        }
}
}
