package com.sitionix.forgeai.api.agentproxy;

import java.util.List;
import java.util.UUID;

public record AgentWorkflowRunGraphResponse(
        UUID taskInputPortId,
        UUID taskOutputPortId,
        List<AgentRunNodeResponse> nodes,
        List<AgentRunPortResponse> ports,
        List<AgentRunConnectionResponse> connections
) {
    public AgentWorkflowRunGraphResponse(final UUID taskInputPortId,
                                         final List<AgentRunNodeResponse> nodes,
                                         final List<AgentRunPortResponse> ports,
                                         final List<AgentRunConnectionResponse> connections) {
        this(taskInputPortId, null, nodes, ports, connections);
    }
}
