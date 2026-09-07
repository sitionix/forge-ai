package com.sitionix.forgeagent.domain.model;

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
        NodeScopeMode scopeMode,
        NodeContextMode contextMode
) {
    public Node {
        Objects.requireNonNull(scopeMode, "scopeMode must not be null");
        contextMode = NodeContextMode.legacyDefault(contextMode);
    }

    public Node(final UUID id,
                final UUID targetId,
                final NodeInputMode inputMode,
                final List<NodePort> inputs,
                final List<NodePort> outputs,
                final NodePosition position,
                final NodeScopeMode scopeMode) {
        this(id, targetId, inputMode, inputs, outputs, position, scopeMode, NodeContextMode.FRESH_EACH_NODE_RUN);
    }

}
