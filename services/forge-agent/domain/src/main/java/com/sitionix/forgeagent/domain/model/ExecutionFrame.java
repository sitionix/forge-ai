package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record ExecutionFrame(
        UUID id,
        UUID workflowRunId,
        UUID parentFrameId,
        Instant createdAt
) {
}
