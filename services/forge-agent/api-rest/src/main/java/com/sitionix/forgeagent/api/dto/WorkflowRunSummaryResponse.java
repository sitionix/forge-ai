package com.sitionix.forgeagent.api.dto;

import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import java.time.Instant;
import java.util.UUID;

public record WorkflowRunSummaryResponse(
        UUID id,
        UUID sourceWorkflowId,
        String workflowName,
        WorkflowRunStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
}
