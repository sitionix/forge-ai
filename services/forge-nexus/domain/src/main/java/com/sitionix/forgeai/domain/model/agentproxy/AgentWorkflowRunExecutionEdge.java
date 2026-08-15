package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.UUID;

public record AgentWorkflowRunExecutionEdge(
        UUID sourceNodeRunId,
        UUID targetNodeRunId,
        String sourceType
) {
}
