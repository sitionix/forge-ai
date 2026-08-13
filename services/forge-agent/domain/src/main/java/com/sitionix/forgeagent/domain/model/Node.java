package com.sitionix.forgeagent.domain.model;

import java.util.List;
import java.util.UUID;

public record Node(
        UUID id,
        UUID targetId,
        List<UUID> dependsOnNodeIds,
        NodeInputMode inputMode,
        NodePosition position
) {
    public Node(final UUID id,
                final UUID targetId,
                final List<UUID> dependsOnNodeIds,
                final NodePosition position) {
        this(id, targetId, dependsOnNodeIds, NodeInputMode.DEPENDENCIES_ONLY, position);
    }
}
