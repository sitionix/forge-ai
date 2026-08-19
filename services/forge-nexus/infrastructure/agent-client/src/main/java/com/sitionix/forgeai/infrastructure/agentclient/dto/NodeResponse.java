package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.List;
import java.util.UUID;

public record NodeResponse(
        UUID id,
        UUID targetId,
        String inputMode,
        List<NodePortResponse> inputs,
        List<NodePortResponse> outputs,
        NodePositionResponse position,
        String scopeMode
) {
    public NodeResponse(final UUID id, final UUID targetId, final String inputMode, final List<NodePortResponse> inputs,
                        final List<NodePortResponse> outputs, final NodePositionResponse position) {
        this(id, targetId, inputMode, inputs, outputs, position, "GLOBAL");
    }
    public NodeResponse(final UUID id,
                        final UUID targetId,
                        final String inputMode,
                        final NodePositionResponse position) {
        this(id, targetId, inputMode, List.of(), List.of(), position, "GLOBAL");
    }

    public NodeResponse(final UUID id,
                        final UUID targetId,
                        final NodePositionResponse position) {
        this(id, targetId, null, List.of(), List.of(), position, "GLOBAL");
    }
}
