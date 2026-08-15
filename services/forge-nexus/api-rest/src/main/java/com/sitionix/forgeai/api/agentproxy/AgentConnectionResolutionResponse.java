package com.sitionix.forgeai.api.agentproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.sitionix.forgeai.domain.model.agentproxy.ConnectionResolutionType;
import java.time.Instant;
import java.util.UUID;

public record AgentConnectionResolutionResponse(
        UUID id,
        UUID executionFrameId,
        UUID sourceNodeRunId,
        UUID sourceConnectionId,
        UUID targetInputPortId,
        ConnectionResolutionType resolutionType,
        JsonNode payload,
        UUID consumedByNodeRunId,
        Instant createdAt
) {
}
