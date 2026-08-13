package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record ProjectTaskSummary(
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
