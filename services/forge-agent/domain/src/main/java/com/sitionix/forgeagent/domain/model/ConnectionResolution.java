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
        Instant createdAt
) {
}
