package com.sitionix.forgeai.domain.model.agentproxy;

import java.time.Instant;
import java.util.UUID;

public record AgentConnectionResolution(
        UUID id,
        UUID executionFrameId,
        UUID sourceNodeRunId,
        UUID sourceConnectionId,
        UUID targetInputPortId,
        ConnectionResolutionType resolutionType,
        AgentNodeRunOutputDocument payload,
        UUID consumedByNodeRunId,
        Instant createdAt,
        UUID targetRepositoryId
) {
}
