package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunStatus;
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
        AgentWorkflowRunStatus status,
        List<NodeRunResponse> nodeRuns,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
}
