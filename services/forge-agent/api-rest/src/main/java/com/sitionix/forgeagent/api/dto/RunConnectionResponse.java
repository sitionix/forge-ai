package com.sitionix.forgeagent.api.dto;

import java.util.UUID;

public record RunConnectionResponse(
        UUID sourceConnectionId,
        UUID sourceOutputPortId,
        UUID targetInputPortId
) {
}
