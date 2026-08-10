package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkflowRun(
        UUID id,
        UUID projectId,
        UUID sourceWorkflowId,
        String workflowName,
        String input,
        WorkflowRunStatus status,
        List<NodeRun> nodeRuns,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
}
