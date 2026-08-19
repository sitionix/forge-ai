package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.UUID;

public record RunNodeResponse(
        UUID sourceNodeId,
        String agentName,
        NodePositionResponse position,
        String scopeMode
) {
    public RunNodeResponse(final UUID sourceNodeId, final String agentName, final NodePositionResponse position) {
        this(sourceNodeId, agentName, position, "GLOBAL");
    }
}
