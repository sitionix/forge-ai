package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.List;
import java.util.UUID;

public record Node(
        UUID id,
        UUID targetId,
        List<UUID> dependsOnNodeIds,
        NodePosition position
) {
}
