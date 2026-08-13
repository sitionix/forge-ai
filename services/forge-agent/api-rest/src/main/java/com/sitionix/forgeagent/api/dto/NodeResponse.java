package com.sitionix.forgeagent.api.dto;

import java.util.List;
import java.util.UUID;

public record NodeResponse(
        UUID id,
        UUID targetId,
        List<UUID> dependsOnNodeIds,
        String inputMode,
        NodePositionResponse position
) {
    public NodeResponse(final UUID id,
                        final UUID targetId,
                        final List<UUID> dependsOnNodeIds,
                        final NodePositionResponse position) {
        this(id, targetId, dependsOnNodeIds, null, position);
    }
}
