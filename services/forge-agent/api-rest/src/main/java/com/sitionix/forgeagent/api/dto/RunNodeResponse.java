package com.sitionix.forgeagent.api.dto;

import java.util.UUID;

public record RunNodeResponse(
        UUID sourceNodeId,
        String agentName,
        NodePositionResponse position,
        String scopeMode,
        String contextMode,
        String contextGroupKey,
        String nodeType) {
    public RunNodeResponse {
        nodeType = nodeType == null ? "AGENT" : nodeType;
    }

    public RunNodeResponse(UUID sourceNodeId,
        String agentName,
        NodePositionResponse position,
        String scopeMode,
        String contextMode,
        String contextGroupKey) {
        this(sourceNodeId, agentName, position, scopeMode, contextMode, contextGroupKey, "AGENT");
    }

    public RunNodeResponse(final UUID sourceNodeId, final String agentName,
                           final NodePositionResponse position, final String scopeMode) {
        this(sourceNodeId, agentName, position, scopeMode, "FRESH_EACH_NODE_RUN");
    }

    public RunNodeResponse(UUID sourceNodeId,
        String agentName,
        NodePositionResponse position,
        String scopeMode,
        String contextMode) {
        this(sourceNodeId, agentName, position, scopeMode, contextMode, null);
    }
}
