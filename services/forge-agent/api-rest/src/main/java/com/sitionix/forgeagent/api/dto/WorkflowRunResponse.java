package com.sitionix.forgeagent.api.dto;

import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkflowRunResponse(
        UUID id,
        UUID projectId,
        UUID sourceWorkflowId,
        UUID taskId,
        String workflowName,
        String input,
        WorkflowRunStatus status,
        List<NodeRunResponse> nodeRuns,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
}
