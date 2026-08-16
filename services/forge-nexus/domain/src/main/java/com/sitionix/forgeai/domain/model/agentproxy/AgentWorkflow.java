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
        Instant createdAt,
        Instant updatedAt
) {
}
