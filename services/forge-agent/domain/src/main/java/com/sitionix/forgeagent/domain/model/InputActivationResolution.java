package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record InputActivationResolution(
        UUID id,
        UUID workflowRunId,
        UUID activationFrameId,
        UUID targetInputPortId,
        UUID activatedNodeRunId,
        Instant createdAt,
        UUID repositoryId
) {
    public InputActivationResolution(final UUID id, final UUID workflowRunId, final UUID activationFrameId,
                                     final UUID targetInputPortId, final UUID activatedNodeRunId, final Instant createdAt) {
        this(id, workflowRunId, activationFrameId, targetInputPortId, activatedNodeRunId, createdAt, null);
    }
}
