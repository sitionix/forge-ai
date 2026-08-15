package com.sitionix.forgeagent.api.dto;

import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
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
        WorkflowRunStatus status,
        List<NodeRunResponse> nodeRuns,
        List<ConnectionResolutionResponse> connectionResolutions,
        List<WorkflowRunExecutionEdgeResponse> executionEdges,
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
                               final WorkflowRunStatus status,
                               final List<NodeRunResponse> nodeRuns,
                               final Instant createdAt,
                               final Instant startedAt,
                               final Instant finishedAt) {
        this(id, projectId, sourceWorkflowId, taskId, workflowName, input, status, nodeRuns, List.of(), List.of(), createdAt, startedAt, finishedAt);
    }
}
