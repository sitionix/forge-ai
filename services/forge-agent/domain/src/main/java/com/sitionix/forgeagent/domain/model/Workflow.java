package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Workflow(
        UUID id,
        UUID projectId,
        String name,
        String normalizedName,
        List<Node> nodes,
        List<WorkflowConnection> connections,
        UUID taskInputPortId,
        UUID taskOutputPortId,
        Instant createdAt,
        Instant updatedAt
) {
    public Workflow(final UUID id,
                    final UUID projectId,
                    final String name,
                    final String normalizedName,
                    final List<Node> nodes,
                    final List<WorkflowConnection> connections,
                    final UUID taskInputPortId,
                    final Instant createdAt,
                    final Instant updatedAt) {
        this(id, projectId, name, normalizedName, nodes, connections, taskInputPortId, null, createdAt, updatedAt);
    }
}
