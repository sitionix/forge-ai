package com.sitionix.forgeagent.api.dto;

import java.util.UUID;

public record RecoveredNodeRunRetryResponse(UUID nodeRunId, WorkflowRunResponse workflowRun) {
}
