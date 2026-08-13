package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record WorkflowRunSummary(
        UUID id,
        UUID sourceWorkflowId,
        UUID taskId,
        String workflowName,
        WorkflowRunStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
}
