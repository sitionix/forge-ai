package com.sitionix.forgeagent.api.dto;

import java.util.List;
import java.util.UUID;

public record NodeRequest(
        UUID id,
        UUID targetId,
        List<UUID> dependsOnNodeIds,
        String inputMode,
        NodePositionRequest position
) {
    public NodeRequest(final UUID id,
                       final UUID targetId,
                       final List<UUID> dependsOnNodeIds,
                       final NodePositionRequest position) {
        this(id, targetId, dependsOnNodeIds, null, position);
    }
}
