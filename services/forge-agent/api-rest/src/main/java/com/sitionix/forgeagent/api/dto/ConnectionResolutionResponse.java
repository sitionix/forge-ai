package com.sitionix.forgeagent.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.sitionix.forgeagent.domain.model.ConnectionResolutionType;
import java.time.Instant;
import java.util.UUID;

public record ConnectionResolutionResponse(
        UUID id,
        UUID executionFrameId,
        UUID sourceNodeRunId,
        UUID sourceConnectionId,
        UUID targetInputPortId,
        ConnectionResolutionType resolutionType,
        JsonNode payload,
        UUID consumedByNodeRunId,
        Instant createdAt,
        UUID targetRepositoryId
) {
}
