package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.UUID;

public record AgentRunNode(
        UUID sourceNodeId,
        String agentName,
        NodePosition position,
        String scopeMode,
        String contextMode,
        String contextGroupKey,
        AgentNodeType nodeType) {
    public AgentRunNode {
        if (nodeType == null) {
            nodeType = AgentNodeType.AGENT;
        }
    }

    public AgentRunNode(
        UUID sourceNodeId,
        String agentName,
        NodePosition position,
        String scopeMode,
        String contextMode,
        String contextGroupKey) {
        this(sourceNodeId, agentName, position, scopeMode, contextMode, contextGroupKey, AgentNodeType.AGENT);
    }

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
