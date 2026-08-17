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
        Instant createdAt,
        Instant updatedAt
) {
}
