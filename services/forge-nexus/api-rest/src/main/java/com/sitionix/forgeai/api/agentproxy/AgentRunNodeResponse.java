package com.sitionix.forgeai.api.agentproxy;

import java.util.UUID;

public record AgentRunNodeResponse(
        UUID sourceNodeId,
        String agentName,
        NodePositionResponse position,
        String scopeMode,
        String contextMode,
        String contextGroupKey) {
    public AgentRunNodeResponse(UUID sourceNodeId,String agentName,NodePositionResponse position,String scopeMode) { this(sourceNodeId,agentName,position,scopeMode,null); }

    public AgentRunNodeResponse(UUID sourceNodeId,
        String agentName,
        NodePositionResponse position,
        String scopeMode,
        String contextMode) {
        this(sourceNodeId, agentName, position, scopeMode, contextMode, null);
    }
}
