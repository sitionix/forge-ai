package com.sitionix.forgeagent.api.dto;

import com.sitionix.forgeagent.domain.model.PortDirection;
import java.util.UUID;

public record RunPortResponse(
        UUID sourcePortId,
        UUID sourceNodeId,
        PortDirection direction,
        String name,
        String description,
        int order
) {
}
