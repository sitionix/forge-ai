package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.List;
import java.util.UUID;

public record NodeRequest(
        UUID id,
        UUID targetId,
        List<UUID> dependsOnNodeIds,
        String inputMode,
        List<NodePortRequest> inputs,
        List<NodePortRequest> outputs,
        NodePositionRequest position
) {
    public NodeRequest(final UUID id,
                       final UUID targetId,
                       final List<UUID> dependsOnNodeIds,
                       final String inputMode,
                       final NodePositionRequest position) {
        this(id, targetId, dependsOnNodeIds, inputMode, null, null, position);
    }

    public NodeRequest(final UUID id,
                       final UUID targetId,
                       final List<UUID> dependsOnNodeIds,
                       final NodePositionRequest position) {
        this(id, targetId, dependsOnNodeIds, null, null, null, position);
    }
}
