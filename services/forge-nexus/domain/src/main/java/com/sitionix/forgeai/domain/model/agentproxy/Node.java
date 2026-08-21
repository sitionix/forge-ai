package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.List;
import java.util.UUID;

public record Node(
        UUID id,
        UUID targetId,
        String inputMode,
        List<NodePort> inputs,
        List<NodePort> outputs,
        NodePosition position,
        String scopeMode
) {
}
