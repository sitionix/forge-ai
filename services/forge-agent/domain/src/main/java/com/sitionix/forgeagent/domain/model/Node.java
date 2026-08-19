package com.sitionix.forgeagent.domain.model;

import java.util.List;
import java.util.UUID;

public record Node(
        UUID id,
        UUID targetId,
        NodeInputMode inputMode,
        List<NodePort> inputs,
        List<NodePort> outputs,
        NodePosition position,
        NodeScopeMode scopeMode
) {
    public Node {
        scopeMode = scopeMode == null ? NodeScopeMode.GLOBAL : scopeMode;
    }

    public Node(final UUID id, final UUID targetId, final NodeInputMode inputMode,
                final List<NodePort> inputs, final List<NodePort> outputs, final NodePosition position) {
        this(id, targetId, inputMode, inputs, outputs, position, NodeScopeMode.GLOBAL);
    }
    public Node(final UUID id,
                final UUID targetId,
                final NodeInputMode inputMode,
                final NodePosition position) {
        this(id, targetId, inputMode, List.of(), List.of(), position, NodeScopeMode.GLOBAL);
    }

    public Node(final UUID id,
                final UUID targetId,
                final NodePosition position) {
        this(id, targetId, NodeInputMode.DEPENDENCIES_ONLY, List.of(), List.of(), position, NodeScopeMode.GLOBAL);
    }
}
