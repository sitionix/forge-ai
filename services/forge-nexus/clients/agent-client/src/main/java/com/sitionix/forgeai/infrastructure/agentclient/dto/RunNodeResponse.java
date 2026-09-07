package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.UUID;

public record RunNodeResponse(
        UUID sourceNodeId,
        String agentName,
        NodePositionResponse position,
        String scopeMode,
        String contextMode
) {
    public RunNodeResponse(UUID sourceNodeId,String agentName,NodePositionResponse position,String scopeMode) { this(sourceNodeId,agentName,position,scopeMode,null); }
}
