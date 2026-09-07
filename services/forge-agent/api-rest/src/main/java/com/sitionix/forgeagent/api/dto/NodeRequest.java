package com.sitionix.forgeagent.api.dto;

import java.util.List;
import java.util.UUID;

public record NodeRequest(
        UUID id,
        UUID targetId,
        String inputMode,
        List<NodePortRequest> inputs,
        List<NodePortRequest> outputs,
        NodePositionRequest position,
        String scopeMode,
        String contextMode
) {
    public NodeRequest(final UUID id, final UUID targetId, final String inputMode,
                       final List<NodePortRequest> inputs, final List<NodePortRequest> outputs,
                       final NodePositionRequest position, final String scopeMode) {
        this(id, targetId, inputMode, inputs, outputs, position, scopeMode, null);
    }
}
