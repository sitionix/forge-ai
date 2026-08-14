package com.sitionix.forgeagent.api.dto;

import java.util.UUID;

public record WorkflowConnectionResponse(
        UUID id,
        UUID sourceOutputPortId,
        UUID targetInputPortId
) {
}
