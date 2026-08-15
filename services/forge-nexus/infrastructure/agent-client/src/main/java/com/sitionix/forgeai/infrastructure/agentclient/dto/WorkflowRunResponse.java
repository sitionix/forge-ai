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
        List<ConnectionResolutionResponse> connectionResolutions,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
    public WorkflowRunResponse(final UUID id,
                               final UUID projectId,
                               final UUID sourceWorkflowId,
                               final UUID taskId,
                               final String workflowName,
                               final String input,
                               final AgentWorkflowRunStatus status,
                               final List<NodeRunResponse> nodeRuns,
                               final Instant createdAt,
                               final Instant startedAt,
                               final Instant finishedAt) {
        this(id, projectId, sourceWorkflowId, taskId, workflowName, input, status, nodeRuns, List.of(), createdAt, startedAt, finishedAt);
    }
}
