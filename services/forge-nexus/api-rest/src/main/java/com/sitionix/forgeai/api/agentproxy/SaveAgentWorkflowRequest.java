package com.sitionix.forgeai.api.agentproxy;

import java.util.List;
import java.util.UUID;

public record SaveAgentWorkflowRequest(
        String name,
        List<NodeRequest> nodes,
        List<WorkflowConnectionRequest> connections,
        UUID taskInputPortId,
        UUID taskOutputPortId
) {
}
