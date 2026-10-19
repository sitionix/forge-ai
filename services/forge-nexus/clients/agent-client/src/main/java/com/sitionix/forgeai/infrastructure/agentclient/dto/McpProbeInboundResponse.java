package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.List;

public record McpProbeInboundResponse(String protocolVersion, List<Tool> tools) {
    public record Tool(String name, String description, String schemaFingerprint, java.util.Map<String,Object> inputSchema, java.util.Map<String,Object> outputSchema) {
        public Tool(String name, String description, String schemaFingerprint) {
            this(name, description, schemaFingerprint, null, null);
        }
}
}
