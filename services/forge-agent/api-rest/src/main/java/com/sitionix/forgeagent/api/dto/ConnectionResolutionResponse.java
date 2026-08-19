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
    public ConnectionResolutionResponse(final UUID id, final UUID executionFrameId, final UUID sourceNodeRunId,
                                        final UUID sourceConnectionId, final UUID targetInputPortId,
                                        final ConnectionResolutionType resolutionType, final JsonNode payload,
                                        final UUID consumedByNodeRunId, final Instant createdAt) {
        this(id, executionFrameId, sourceNodeRunId, sourceConnectionId, targetInputPortId, resolutionType,
                payload, consumedByNodeRunId, createdAt, null);
    }
}
