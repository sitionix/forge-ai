package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.UUID;

public record AgentRunNode(
        UUID sourceNodeId,
        String agentName,
        NodePosition position,
        String scopeMode,
        String contextMode
) {
    public AgentRunNode(UUID sourceNodeId,String agentName,NodePosition position,String scopeMode) {
        this(sourceNodeId,agentName,position,scopeMode,null);
    }
}
