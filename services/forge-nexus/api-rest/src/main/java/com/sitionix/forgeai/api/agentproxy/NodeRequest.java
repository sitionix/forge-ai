package com.sitionix.forgeai.api.agentproxy;

import java.util.List;
import java.util.UUID;

public record NodeRequest(
        UUID id,
        UUID targetId,
        String inputMode,
        List<NodePortRequest> inputs,
        List<NodePortRequest> outputs,
        NodePositionRequest position,
        String scopeMode
) {
    public NodeRequest(final UUID id, final UUID targetId, final String inputMode, final List<NodePortRequest> inputs,
                       final List<NodePortRequest> outputs, final NodePositionRequest position) {
        this(id, targetId, inputMode, inputs, outputs, position, "GLOBAL");
    }
    public NodeRequest(final UUID id,
                       final UUID targetId,
                       final String inputMode,
                       final NodePositionRequest position) {
        this(id, targetId, inputMode, null, null, position, "GLOBAL");
    }

    public NodeRequest(final UUID id,
                       final UUID targetId,
                       final NodePositionRequest position) {
        this(id, targetId, null, null, null, position, "GLOBAL");
    }
}
