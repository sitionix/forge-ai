package com.sitionix.forgeai.api.agentproxy;

import java.util.UUID;

public record AgentRunNodeResponse(
        UUID sourceNodeId,
        String agentName,
        NodePositionResponse position
) {
}
