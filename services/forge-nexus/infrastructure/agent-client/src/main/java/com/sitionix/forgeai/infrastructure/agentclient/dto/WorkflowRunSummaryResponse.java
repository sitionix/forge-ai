package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunStatus;
import java.time.Instant;
import java.util.UUID;

public record WorkflowRunSummaryResponse(
        UUID id,
        UUID sourceWorkflowId,
        String workflowName,
        AgentWorkflowRunStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
}
