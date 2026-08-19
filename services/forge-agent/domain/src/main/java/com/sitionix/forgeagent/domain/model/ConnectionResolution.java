package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record ConnectionResolution(
        UUID id,
        UUID workflowRunId,
        UUID executionFrameId,
        UUID sourceNodeRunId,
        UUID sourceConnectionId,
        UUID targetInputPortId,
        ConnectionResolutionType type,
        NodeRunOutput payload,
        UUID consumedByNodeRunId,
        Instant createdAt,
        UUID targetRepositoryId
) {
    public ConnectionResolution(final UUID id, final UUID workflowRunId, final UUID executionFrameId,
                                final UUID sourceNodeRunId, final UUID sourceConnectionId, final UUID targetInputPortId,
                                final ConnectionResolutionType type, final NodeRunOutput payload,
                                final UUID consumedByNodeRunId, final Instant createdAt) {
        this(id, workflowRunId, executionFrameId, sourceNodeRunId, sourceConnectionId, targetInputPortId,
                type, payload, consumedByNodeRunId, createdAt, null);
    }
}
