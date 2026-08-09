package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.List;
import java.util.UUID;

public record NodeRequest(
        UUID id,
        UUID targetId,
        List<UUID> dependsOnNodeIds,
        NodePositionRequest position
) {
}
