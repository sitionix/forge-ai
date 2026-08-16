package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.UUID;

public record AgentRunConnection(
        UUID sourceConnectionId,
        UUID sourceOutputPortId,
        UUID targetInputPortId
) {
}
