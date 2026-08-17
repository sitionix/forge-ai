package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.List;
import java.util.UUID;

public record SaveAgentWorkflowCommand(
        String name,
        List<Node> nodes,
        List<WorkflowConnection> connections,
        UUID taskInputPortId
) {
}
