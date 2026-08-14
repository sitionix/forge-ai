package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.UUID;

public record WorkflowConnectionRequest(
        UUID id,
        UUID sourceOutputPortId,
        UUID targetInputPortId
) {
}
