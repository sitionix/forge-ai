package com.sitionix.forgeai.api.agentproxy;

import java.util.UUID;

public record AgentRunNodeResponse(
        UUID sourceNodeId,
        String agentName,
        NodePositionResponse position,
        String scopeMode
) {
    public AgentRunNodeResponse(final UUID sourceNodeId, final String agentName, final NodePositionResponse position) {
        this(sourceNodeId, agentName, position, "GLOBAL");
    }
}
