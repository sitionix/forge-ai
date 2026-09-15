package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.UUID;

public record RecoveredAgentNodeRunRetry(UUID nodeRunId, AgentWorkflowRun workflowRun) {
}
