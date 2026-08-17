package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.List;
import java.util.UUID;

public record AgentWorkflowRunGraph(
        UUID taskInputPortId,
        UUID taskOutputPortId,
        List<AgentRunNode> nodes,
        List<AgentRunPort> ports,
        List<AgentRunConnection> connections
) {
    public AgentWorkflowRunGraph(final UUID taskInputPortId,
                                 final List<AgentRunNode> nodes,
                                 final List<AgentRunPort> ports,
                                 final List<AgentRunConnection> connections) {
        this(taskInputPortId, null, nodes, ports, connections);
    }
}
