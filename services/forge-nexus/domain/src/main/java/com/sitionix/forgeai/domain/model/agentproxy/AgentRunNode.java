package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.UUID;

public record AgentRunNode(
        UUID sourceNodeId,
        String agentName,
        NodePosition position,
        String scopeMode,
        String contextMode,
        String contextGroupKey) {
    public AgentRunNode(UUID sourceNodeId,String agentName,NodePosition position,String scopeMode) {
        this(sourceNodeId,agentName,position,scopeMode,null);
    }

    public AgentRunNode(UUID sourceNodeId,
        String agentName,
        NodePosition position,
        String scopeMode,
        String contextMode) {
        this(sourceNodeId, agentName, position, scopeMode, contextMode, null);
    }
}
