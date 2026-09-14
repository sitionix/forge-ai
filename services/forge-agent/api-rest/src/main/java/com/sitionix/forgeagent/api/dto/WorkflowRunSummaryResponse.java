package com.sitionix.forgeagent.api.dto;

import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.model.OperatorStopStatus;
import java.time.Instant;
import java.util.UUID;

public record WorkflowRunSummaryResponse(
        UUID id,
        UUID sourceWorkflowId,
        UUID taskId,
        String workflowName,
        WorkflowRunStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        OperatorStopStatus operatorStopStatus,
        String operatorStopFailureCode
) {
    public WorkflowRunSummaryResponse(
            final UUID id, final UUID sourceWorkflowId, final UUID taskId, final String workflowName,
            final WorkflowRunStatus status, final Instant createdAt, final Instant startedAt,
            final Instant finishedAt
    ) {
        this(id, sourceWorkflowId, taskId, workflowName, status, createdAt, startedAt, finishedAt, null, null);
    }
}
