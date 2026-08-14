package com.sitionix.forgeai.api.agentproxy;

import java.util.UUID;

public record WorkflowConnectionResponse(
        UUID id,
        UUID sourceOutputPortId,
        UUID targetInputPortId
) {
}
