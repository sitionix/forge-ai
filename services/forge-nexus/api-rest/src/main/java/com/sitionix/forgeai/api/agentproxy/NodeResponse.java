package com.sitionix.forgeai.api.agentproxy;

import java.util.List;
import java.util.UUID;

public record NodeResponse(
        UUID id,
        UUID targetId,
        List<UUID> dependsOnNodeIds,
        NodePositionResponse position
) {
}
