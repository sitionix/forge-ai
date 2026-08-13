package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunStatus;
import java.time.Instant;
import java.util.UUID;

public record ProjectTaskSummaryResponse(
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
