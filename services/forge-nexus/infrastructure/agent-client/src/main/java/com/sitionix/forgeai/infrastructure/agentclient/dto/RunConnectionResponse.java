package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.UUID;

public record RunConnectionResponse(
        UUID sourceConnectionId,
        UUID sourceOutputPortId,
        UUID targetInputPortId
) {
}
