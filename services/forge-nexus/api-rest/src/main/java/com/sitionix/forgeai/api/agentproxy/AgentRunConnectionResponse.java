package com.sitionix.forgeai.api.agentproxy;

import java.util.UUID;

public record AgentRunConnectionResponse(
        UUID sourceConnectionId,
        UUID sourceOutputPortId,
        UUID targetInputPortId
) {
}
