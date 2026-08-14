package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.List;

public record SaveAgentWorkflowRequest(
        String name,
        List<NodeRequest> nodes,
        List<WorkflowConnectionRequest> connections
) {
}
