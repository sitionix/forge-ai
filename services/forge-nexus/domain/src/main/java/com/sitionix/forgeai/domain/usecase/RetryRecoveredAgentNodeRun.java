package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.RecoveredAgentNodeRunRetry;
import java.util.UUID;

public interface RetryRecoveredAgentNodeRun {
    RecoveredAgentNodeRunRetry execute(UUID workflowRunId, UUID nodeRunId);
}
