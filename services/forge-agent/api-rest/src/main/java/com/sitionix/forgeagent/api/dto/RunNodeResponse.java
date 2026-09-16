package com.sitionix.forgeagent.api.dto;

import java.util.UUID;

public record RunNodeResponse(
        UUID sourceNodeId,
        String agentName,
        NodePositionResponse position,
        String scopeMode,
        String contextMode,
        String contextGroupKey) {
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
