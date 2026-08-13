package com.sitionix.forgeai.api.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunStatus;
import java.time.Instant;
import java.util.UUID;

public record AgentProjectTaskSummaryResponse(
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
