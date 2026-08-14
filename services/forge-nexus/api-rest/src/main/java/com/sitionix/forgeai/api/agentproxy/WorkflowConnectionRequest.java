package com.sitionix.forgeai.api.agentproxy;

import java.util.UUID;

public record WorkflowConnectionRequest(
        UUID id,
        UUID sourceOutputPortId,
        UUID targetInputPortId
) {
}
