package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.List;
import java.util.UUID;

public record SaveAgentWorkflowCommand(
        String name,
        List<Node> nodes,
        List<WorkflowConnection> connections,
        UUID taskInputPortId,
        UUID taskOutputPortId
) {
    public SaveAgentWorkflowCommand(final String name,
                                    final List<Node> nodes,
                                    final List<WorkflowConnection> connections,
                                    final UUID taskInputPortId) {
        this(name, nodes, connections, taskInputPortId, null);
    }
}
