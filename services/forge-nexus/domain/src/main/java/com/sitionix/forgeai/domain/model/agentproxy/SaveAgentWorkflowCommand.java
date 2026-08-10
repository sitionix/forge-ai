package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.List;

public record SaveAgentWorkflowCommand(
        String name,
        List<Node> nodes
) {
}
