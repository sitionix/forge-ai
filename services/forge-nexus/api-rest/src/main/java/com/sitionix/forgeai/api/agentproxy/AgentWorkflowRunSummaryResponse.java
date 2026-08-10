package com.sitionix.forgeai.api.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunStatus;
import java.time.Instant;
import java.util.UUID;

public record AgentWorkflowRunSummaryResponse(
        UUID id,
        UUID sourceWorkflowId,
        String workflowName,
        AgentWorkflowRunStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
}
