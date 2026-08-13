package com.sitionix.forgeai.domain.model.agentproxy;

import java.time.Instant;
import java.util.UUID;

public record AgentProjectTaskSummary(
        UUID id,
        UUID projectId,
        String title,
        UUID workflowId,
        String workflowName,
        UUID latestWorkflowRunId,
        AgentWorkflowRunStatus executionStatus,
        Instant createdAt,
        Instant updatedAt
) {
}
