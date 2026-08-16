package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.UUID;

public record RunPortResponse(
        UUID sourcePortId,
        UUID sourceNodeId,
        String direction,
        String name,
        int order
) {
}
