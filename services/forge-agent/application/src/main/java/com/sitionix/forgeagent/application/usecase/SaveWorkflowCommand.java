package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.WorkflowConnection;
import java.util.List;
import java.util.UUID;

public record SaveWorkflowCommand(
        String name,
        List<Node> nodes,
        List<WorkflowConnection> connections,
        UUID taskInputPortId,
        UUID taskOutputPortId
) {
    public SaveWorkflowCommand(final String name,
                               final List<Node> nodes,
                               final List<WorkflowConnection> connections,
                               final UUID taskInputPortId) {
        this(name, nodes, connections, taskInputPortId, null);
    }
}
