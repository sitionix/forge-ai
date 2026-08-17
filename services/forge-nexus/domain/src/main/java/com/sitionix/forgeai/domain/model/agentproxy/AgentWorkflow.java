package com.sitionix.forgeai.domain.model.agentproxy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentWorkflow(
        UUID id,
        UUID projectId,
        String name,
        List<Node> nodes,
        List<WorkflowConnection> connections,
        UUID taskInputPortId,
        UUID taskOutputPortId,
        Instant createdAt,
        Instant updatedAt
) {
    public AgentWorkflow(final UUID id,
                         final UUID projectId,
                         final String name,
                         final List<Node> nodes,
                         final List<WorkflowConnection> connections,
                         final UUID taskInputPortId,
                         final Instant createdAt,
                         final Instant updatedAt) {
        this(id, projectId, name, nodes, connections, taskInputPortId, null, createdAt, updatedAt);
    }
}
