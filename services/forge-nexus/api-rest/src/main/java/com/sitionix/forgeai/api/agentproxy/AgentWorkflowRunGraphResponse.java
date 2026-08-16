package com.sitionix.forgeai.api.agentproxy;

import java.util.List;

public record AgentWorkflowRunGraphResponse(
        List<AgentRunNodeResponse> nodes,
        List<AgentRunPortResponse> ports,
        List<AgentRunConnectionResponse> connections
) {
}
