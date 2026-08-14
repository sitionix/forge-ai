package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.List;
import java.util.UUID;

public record NodeResponse(
        UUID id,
        UUID targetId,
        String inputMode,
        List<NodePortResponse> inputs,
        List<NodePortResponse> outputs,
        NodePositionResponse position
) {
    public NodeResponse(final UUID id,
                        final UUID targetId,
                        final String inputMode,
                        final NodePositionResponse position) {
        this(id, targetId, inputMode, List.of(), List.of(), position);
    }

    public NodeResponse(final UUID id,
                        final UUID targetId,
                        final NodePositionResponse position) {
        this(id, targetId, null, List.of(), List.of(), position);
    }
}
