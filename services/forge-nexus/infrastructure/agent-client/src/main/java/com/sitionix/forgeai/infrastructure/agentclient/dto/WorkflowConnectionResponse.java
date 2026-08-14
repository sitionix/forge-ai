package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.UUID;

public record WorkflowConnectionResponse(
        UUID id,
        UUID sourceOutputPortId,
        UUID targetInputPortId
) {
}
