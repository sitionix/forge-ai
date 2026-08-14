package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.List;
import java.util.UUID;

public record NodeRequest(
        UUID id,
        UUID targetId,
        String inputMode,
        List<NodePortRequest> inputs,
        List<NodePortRequest> outputs,
        NodePositionRequest position
) {
    public NodeRequest(final UUID id,
                       final UUID targetId,
                       final String inputMode,
                       final NodePositionRequest position) {
        this(id, targetId, inputMode, null, null, position);
    }

    public NodeRequest(final UUID id,
                       final UUID targetId,
                       final NodePositionRequest position) {
        this(id, targetId, null, null, null, position);
    }
}
