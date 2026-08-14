package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.UUID;

public record WorkflowConnection(
        UUID id,
        UUID sourceOutputPortId,
        UUID targetInputPortId
) {
}
