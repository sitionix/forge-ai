package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.List;
import java.util.UUID;

public record Node(
        UUID id,
        UUID targetId,
        List<UUID> dependsOnNodeIds,
        NodeInputMode inputMode,
        List<NodePort> inputs,
        List<NodePort> outputs,
        NodePosition position
) {
    public Node(final UUID id,
                final UUID targetId,
                final List<UUID> dependsOnNodeIds,
                final NodeInputMode inputMode,
                final NodePosition position) {
        this(id, targetId, dependsOnNodeIds, inputMode, List.of(), List.of(), position);
    }

    public Node(final UUID id,
                final UUID targetId,
                final List<UUID> dependsOnNodeIds,
                final NodePosition position) {
        this(id, targetId, dependsOnNodeIds, NodeInputMode.DEPENDENCIES_ONLY, List.of(), List.of(), position);
    }
}
