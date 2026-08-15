package com.sitionix.forgeagent.infrastructure.postgres.entity;

import java.io.Serializable;
import java.util.UUID;

public record WorkflowRunExecutionEdgeEntityId(
        UUID workflowRunId,
        UUID sourceNodeRunId,
        UUID targetNodeRunId,
        String sourceType
) implements Serializable {
}
