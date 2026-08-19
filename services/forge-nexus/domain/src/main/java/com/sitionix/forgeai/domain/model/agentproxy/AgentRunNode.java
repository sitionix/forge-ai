package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.UUID;

public record AgentRunNode(
        UUID sourceNodeId,
        String agentName,
        NodePosition position,
        WorkflowNodeScopeMode scopeMode
) {
    public AgentRunNode(final UUID sourceNodeId, final String agentName, final NodePosition position) {
        this(sourceNodeId, agentName, position, WorkflowNodeScopeMode.GLOBAL);
    }
}
