package com.sitionix.forgeagent.api.mcp;

import com.sitionix.forgeagent.domain.model.McpProbeReport;
import com.sitionix.forgeagent.domain.model.McpToolSummary;
import java.util.List;

public record McpProbeResponse(String protocolVersion, List<Tool> tools) {
    public static McpProbeResponse from(McpProbeReport report) {
        return new McpProbeResponse(report.protocolVersion(), tools(report.tools()));
    }
    public static List<Tool> tools(List<McpToolSummary> source) {
        return source.stream().map(tool -> new Tool(tool.name(), tool.description(), tool.schemaFingerprint(), tool.inputSchema(), tool.outputSchema())).toList();
    }
    public record Tool(String name, String description, String schemaFingerprint, java.util.Map<String,Object> inputSchema, java.util.Map<String,Object> outputSchema) {
        public Tool(String name, String description, String schemaFingerprint) {
            this(name, description, schemaFingerprint, null, null);
        }
}
}
