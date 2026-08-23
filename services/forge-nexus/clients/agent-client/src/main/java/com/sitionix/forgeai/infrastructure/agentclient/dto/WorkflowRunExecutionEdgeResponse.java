package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.UUID;

public record WorkflowRunExecutionEdgeResponse(
        UUID sourceNodeRunId,
        UUID targetNodeRunId,
        String sourceType
) {
}
