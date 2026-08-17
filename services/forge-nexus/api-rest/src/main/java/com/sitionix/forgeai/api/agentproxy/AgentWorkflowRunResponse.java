package com.sitionix.forgeai.api.agentproxy;

import com.fasterxml.jackson.databind.JsonNode;
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
        List<AgentConnectionResolutionResponse> connectionResolutions,
        List<AgentWorkflowRunExecutionEdgeResponse> executionEdges,
        AgentWorkflowRunGraphResponse runtimeGraph,
        JsonNode result,
        UUID resultSourceNodeRunId,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
    public AgentWorkflowRunResponse(final UUID id,
                                    final UUID projectId,
                                    final UUID sourceWorkflowId,
                                    final UUID taskId,
                                    final String workflowName,
                                    final String input,
                                    final AgentWorkflowRunStatus status,
                                    final List<AgentNodeRunResponse> nodeRuns,
                                    final List<AgentConnectionResolutionResponse> connectionResolutions,
                                    final List<AgentWorkflowRunExecutionEdgeResponse> executionEdges,
                                    final Instant createdAt,
                                    final Instant startedAt,
                                    final Instant finishedAt) {
        this(id, projectId, sourceWorkflowId, taskId, workflowName, input, status, nodeRuns, connectionResolutions, executionEdges, null, null, null, createdAt, startedAt, finishedAt);
    }

    public AgentWorkflowRunResponse(final UUID id,
                                    final UUID projectId,
                                    final UUID sourceWorkflowId,
                                    final UUID taskId,
                                    final String workflowName,
                                    final String input,
                                     final AgentWorkflowRunStatus status,
                                     final List<AgentNodeRunResponse> nodeRuns,
                                     final Instant createdAt,
                                     final Instant startedAt,
                                     final Instant finishedAt) {
        this(id, projectId, sourceWorkflowId, taskId, workflowName, input, status, nodeRuns, List.of(), List.of(), null, null, null, createdAt, startedAt, finishedAt);
    }

    public AgentWorkflowRunResponse(final UUID id,
                                    final UUID projectId,
                                    final UUID sourceWorkflowId,
                                    final UUID taskId,
                                    final String workflowName,
                                    final String input,
                                    final AgentWorkflowRunStatus status,
                                    final List<AgentNodeRunResponse> nodeRuns,
                                    final List<AgentConnectionResolutionResponse> connectionResolutions,
                                    final List<AgentWorkflowRunExecutionEdgeResponse> executionEdges,
                                    final AgentWorkflowRunGraphResponse runtimeGraph,
                                    final Instant createdAt,
                                    final Instant startedAt,
                                    final Instant finishedAt) {
        this(id, projectId, sourceWorkflowId, taskId, workflowName, input, status, nodeRuns, connectionResolutions, executionEdges, runtimeGraph, null, null, createdAt, startedAt, finishedAt);
    }
}
