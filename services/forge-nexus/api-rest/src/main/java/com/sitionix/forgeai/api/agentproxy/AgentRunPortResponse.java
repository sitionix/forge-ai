package com.sitionix.forgeai.api.agentproxy;

import java.util.UUID;

public record AgentRunPortResponse(
        UUID sourcePortId,
        UUID sourceNodeId,
        String direction,
        String name,
        String description,
        int order
) {
}
