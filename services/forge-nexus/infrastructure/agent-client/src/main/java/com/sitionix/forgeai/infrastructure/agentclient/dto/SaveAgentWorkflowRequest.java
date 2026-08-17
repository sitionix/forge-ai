package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.List;
import java.util.UUID;

public record SaveAgentWorkflowRequest(
        String name,
        List<NodeRequest> nodes,
        List<WorkflowConnectionRequest> connections,
        UUID taskInputPortId,
        UUID taskOutputPortId
) {
    public SaveAgentWorkflowRequest(final String name,
                                    final List<NodeRequest> nodes,
                                    final List<WorkflowConnectionRequest> connections,
                                    final UUID taskInputPortId) {
        this(name, nodes, connections, taskInputPortId, null);
    }
}
