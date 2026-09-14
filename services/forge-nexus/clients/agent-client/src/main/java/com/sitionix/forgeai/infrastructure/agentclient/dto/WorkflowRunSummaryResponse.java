package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunStatus;
import com.sitionix.forgeai.domain.model.agentproxy.AgentOperatorStopStatus;
import java.time.Instant;
import java.util.UUID;

public record WorkflowRunSummaryResponse(
        UUID id,
        UUID sourceWorkflowId,
        UUID taskId,
        String workflowName,
        AgentWorkflowRunStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        AgentOperatorStopStatus operatorStopStatus,
        String operatorStopFailureCode
) {
    public WorkflowRunSummaryResponse(
            final UUID id, final UUID sourceWorkflowId, final UUID taskId, final String workflowName,
            final AgentWorkflowRunStatus status, final Instant createdAt, final Instant startedAt,
            final Instant finishedAt
    ) {
        this(id, sourceWorkflowId, taskId, workflowName, status, createdAt, startedAt, finishedAt, null, null);
    }
}
