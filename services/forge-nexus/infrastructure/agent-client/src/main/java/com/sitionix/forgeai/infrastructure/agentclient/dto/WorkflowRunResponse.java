package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.fasterxml.jackson.databind.JsonNode;
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
        List<WorkflowRunExecutionEdgeResponse> executionEdges,
        WorkflowRunGraphResponse runtimeGraph,
        JsonNode result,
        UUID resultSourceNodeRunId,
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
                               final List<ConnectionResolutionResponse> connectionResolutions,
                               final List<WorkflowRunExecutionEdgeResponse> executionEdges,
                               final Instant createdAt,
                               final Instant startedAt,
                               final Instant finishedAt) {
        this(id, projectId, sourceWorkflowId, taskId, workflowName, input, status, nodeRuns, connectionResolutions, executionEdges, null, null, null, createdAt, startedAt, finishedAt);
    }

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
        this(id, projectId, sourceWorkflowId, taskId, workflowName, input, status, nodeRuns, List.of(), List.of(), null, null, null, createdAt, startedAt, finishedAt);
    }

    public WorkflowRunResponse(final UUID id,
                               final UUID projectId,
                               final UUID sourceWorkflowId,
                               final UUID taskId,
                               final String workflowName,
                               final String input,
                               final AgentWorkflowRunStatus status,
                               final List<NodeRunResponse> nodeRuns,
                               final List<ConnectionResolutionResponse> connectionResolutions,
                               final List<WorkflowRunExecutionEdgeResponse> executionEdges,
                               final WorkflowRunGraphResponse runtimeGraph,
                               final Instant createdAt,
                               final Instant startedAt,
                               final Instant finishedAt) {
        this(id, projectId, sourceWorkflowId, taskId, workflowName, input, status, nodeRuns, connectionResolutions, executionEdges, runtimeGraph, null, null, createdAt, startedAt, finishedAt);
    }
}
