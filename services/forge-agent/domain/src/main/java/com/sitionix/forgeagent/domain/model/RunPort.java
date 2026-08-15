package com.sitionix.forgeagent.domain.model;

import java.util.UUID;

public record RunPort(
        UUID workflowRunId,
        UUID sourcePortId,
        UUID sourceNodeId,
        PortDirection direction,
        String name,
        String description,
        int order
) {
}
