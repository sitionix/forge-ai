package com.sitionix.forgeagent.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.model.OperatorStopStatus;
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
        WorkflowRunGraphResponse runtimeGraph,
        JsonNode result,
        UUID resultSourceNodeRunId,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        List<UUID> repositoryIds,
        OperatorStopStatus operatorStopStatus,
        String operatorStopFailureCode
) {
    public WorkflowRunResponse(
            final UUID id, final UUID projectId, final UUID sourceWorkflowId, final UUID taskId,
            final String workflowName, final String input, final WorkflowRunStatus status,
            final List<NodeRunResponse> nodeRuns,
            final List<ConnectionResolutionResponse> connectionResolutions,
            final List<WorkflowRunExecutionEdgeResponse> executionEdges,
            final WorkflowRunGraphResponse runtimeGraph, final JsonNode result,
            final UUID resultSourceNodeRunId, final Instant createdAt, final Instant startedAt,
            final Instant finishedAt, final List<UUID> repositoryIds
    ) {
        this(id, projectId, sourceWorkflowId, taskId, workflowName, input, status, nodeRuns,
                connectionResolutions, executionEdges, runtimeGraph, result, resultSourceNodeRunId,
                createdAt, startedAt, finishedAt, repositoryIds, null, null);
    }
}
