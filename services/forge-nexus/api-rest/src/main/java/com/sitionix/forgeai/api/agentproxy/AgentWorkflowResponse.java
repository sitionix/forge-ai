package com.sitionix.forgeai.api.agentproxy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentWorkflowResponse(
        UUID id,
        UUID projectId,
        String name,
        List<NodeResponse> nodes,
        List<WorkflowConnectionResponse> connections,
        UUID taskInputPortId,
        UUID taskOutputPortId,
        Instant createdAt,
        Instant updatedAt
) {
    public AgentWorkflowResponse(final UUID id,
                                 final UUID projectId,
                                 final String name,
                                 final List<NodeResponse> nodes,
                                 final List<WorkflowConnectionResponse> connections,
                                 final UUID taskInputPortId,
                                 final Instant createdAt,
                                 final Instant updatedAt) {
        this(id, projectId, name, nodes, connections, taskInputPortId, null, createdAt, updatedAt);
    }
}
