package com.sitionix.forgeai.api.agentproxy;

import java.util.List;
import java.util.UUID;

public record AgentWorkflowRunGraphResponse(
        UUID taskInputPortId,
        List<AgentRunNodeResponse> nodes,
        List<AgentRunPortResponse> ports,
        List<AgentRunConnectionResponse> connections
) {
}
