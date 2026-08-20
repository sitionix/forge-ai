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
}
