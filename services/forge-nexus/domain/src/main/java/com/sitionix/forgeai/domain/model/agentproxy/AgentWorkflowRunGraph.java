package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.List;
import java.util.UUID;

public record AgentWorkflowRunGraph(
        UUID taskInputPortId,
        List<AgentRunNode> nodes,
        List<AgentRunPort> ports,
        List<AgentRunConnection> connections
) {
}
