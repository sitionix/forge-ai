package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.List;

public record AgentWorkflowRunGraph(
        List<AgentRunNode> nodes,
        List<AgentRunPort> ports,
        List<AgentRunConnection> connections
) {
}
