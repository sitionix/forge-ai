package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.List;
import java.util.UUID;

public record SaveAgentWorkflowRequest(
        String name,
        List<NodeRequest> nodes,
        List<WorkflowConnectionRequest> connections,
        UUID taskInputPortId
) {
}
