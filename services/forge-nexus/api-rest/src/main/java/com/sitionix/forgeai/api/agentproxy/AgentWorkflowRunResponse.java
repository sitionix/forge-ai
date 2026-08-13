package com.sitionix.forgeai.api.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentWorkflowRunResponse(
        UUID id,
        UUID projectId,
        UUID sourceWorkflowId,
        UUID taskId,
        String workflowName,
        String input,
        AgentWorkflowRunStatus status,
        List<AgentNodeRunResponse> nodeRuns,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
}
