package com.sitionix.forgeai.api.agentproxy;

import java.util.UUID;

public record AgentWorkflowRunExecutionEdgeResponse(
        UUID sourceNodeRunId,
        UUID targetNodeRunId,
        String sourceType
) {
}
