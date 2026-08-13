package com.sitionix.forgeagent.api.dto;

import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import java.time.Instant;
import java.util.UUID;

public record ProjectTaskSummaryResponse(
        UUID id,
        UUID projectId,
        String title,
        UUID workflowId,
        String workflowName,
        UUID latestWorkflowRunId,
        WorkflowRunStatus executionStatus,
        Instant createdAt,
        Instant updatedAt
) {
}
