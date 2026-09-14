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
        Instant finishedAt,
        OperatorStopStatus operatorStopStatus,
        String operatorStopFailureCode
) {
    public WorkflowRunSummary(
            final UUID id,
            final UUID sourceWorkflowId,
            final UUID taskId,
            final String workflowName,
            final WorkflowRunStatus status,
            final Instant createdAt,
            final Instant startedAt,
            final Instant finishedAt
    ) {
        this(id, sourceWorkflowId, taskId, workflowName, status, createdAt, startedAt, finishedAt, null, null);
    }
}
