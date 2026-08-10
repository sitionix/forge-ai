package com.sitionix.forgeai.domain.model.agentproxy;

import java.time.Instant;
import java.util.UUID;

public record AgentWorkflowRunSummary(
        UUID id,
        UUID sourceWorkflowId,
        String workflowName,
        AgentWorkflowRunStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
}
