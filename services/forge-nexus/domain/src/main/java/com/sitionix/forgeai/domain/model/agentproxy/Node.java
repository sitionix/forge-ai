package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Node(
        UUID id,
        UUID targetId,
        NodeInputMode inputMode,
        List<NodePort> inputs,
        List<NodePort> outputs,
        NodePosition position,
        WorkflowNodeScopeMode scopeMode
) {
    public Node {
        Objects.requireNonNull(scopeMode, "scopeMode must not be null");
    }

}
