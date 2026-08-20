package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.UUID;
import java.util.Objects;

public record AgentRunNode(
        UUID sourceNodeId,
        String agentName,
        NodePosition position,
        WorkflowNodeScopeMode scopeMode
) {
    public AgentRunNode {
        Objects.requireNonNull(scopeMode, "scopeMode must not be null");
    }

}
