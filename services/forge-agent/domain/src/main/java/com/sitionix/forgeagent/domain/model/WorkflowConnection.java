package com.sitionix.forgeagent.domain.model;

import java.util.UUID;

public record WorkflowConnection(
        UUID id,
        UUID sourceOutputPortId,
        UUID targetInputPortId
) {
}
