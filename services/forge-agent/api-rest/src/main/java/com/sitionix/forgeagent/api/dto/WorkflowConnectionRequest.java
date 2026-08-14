package com.sitionix.forgeagent.api.dto;

import java.util.UUID;

public record WorkflowConnectionRequest(
        UUID id,
        UUID sourceOutputPortId,
        UUID targetInputPortId
) {
}
