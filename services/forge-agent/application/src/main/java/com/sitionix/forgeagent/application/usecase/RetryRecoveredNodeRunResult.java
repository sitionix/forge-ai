package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.model.WorkflowRun;
import java.util.UUID;

public record RetryRecoveredNodeRunResult(UUID nodeRunId, WorkflowRun workflowRun) {
}
