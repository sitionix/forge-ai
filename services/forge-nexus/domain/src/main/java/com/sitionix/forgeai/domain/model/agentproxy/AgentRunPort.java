package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.UUID;

public record AgentRunPort(
        UUID sourcePortId,
        UUID sourceNodeId,
        String direction,
        String name,
        String description,
        int order
) {
}
