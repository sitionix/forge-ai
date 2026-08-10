package com.sitionix.forgeagent.api.dto;

import java.util.List;
import java.util.UUID;

public record NodeRequest(
        UUID id,
        UUID targetId,
        List<UUID> dependsOnNodeIds,
        NodePositionRequest position
) {
}
