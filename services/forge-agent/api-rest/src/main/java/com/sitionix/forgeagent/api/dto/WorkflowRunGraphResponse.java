package com.sitionix.forgeagent.api.dto;

import java.util.List;
import java.util.UUID;

public record WorkflowRunGraphResponse(
        UUID taskInputPortId,
        UUID taskOutputPortId,
        List<RunNodeResponse> nodes,
        List<RunPortResponse> ports,
        List<RunConnectionResponse> connections
) {
    public WorkflowRunGraphResponse(final UUID taskInputPortId,
                                    final List<RunNodeResponse> nodes,
                                    final List<RunPortResponse> ports,
                                    final List<RunConnectionResponse> connections) {
        this(taskInputPortId, null, nodes, ports, connections);
    }
}
