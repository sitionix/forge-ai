package com.sitionix.forgeai.api.agentproxy;

import java.util.UUID;

public record AgentRecoveredNodeRunRetryResponse(UUID nodeRunId, AgentWorkflowRunResponse workflowRun) {
}
