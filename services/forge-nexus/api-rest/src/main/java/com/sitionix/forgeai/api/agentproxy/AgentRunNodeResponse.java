package com.sitionix.forgeai.api.agentproxy;

import java.util.UUID;

public record AgentRunNodeResponse(
        UUID sourceNodeId,
        String agentName,
        NodePositionResponse position,
        String scopeMode,
        String contextMode
) {
    public AgentRunNodeResponse(UUID sourceNodeId,String agentName,NodePositionResponse position,String scopeMode) { this(sourceNodeId,agentName,position,scopeMode,null); }
}
