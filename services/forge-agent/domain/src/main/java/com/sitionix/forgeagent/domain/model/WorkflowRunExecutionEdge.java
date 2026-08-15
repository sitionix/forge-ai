package com.sitionix.forgeagent.domain.model;

import java.util.UUID;

public record WorkflowRunExecutionEdge(
        UUID workflowRunId,
        UUID sourceNodeRunId,
        UUID targetNodeRunId,
        String sourceType
) {
}
