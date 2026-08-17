package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkflowRun(
        UUID id,
        UUID projectId,
        UUID sourceWorkflowId,
        UUID taskId,
        String workflowName,
        String input,
        WorkflowRunStatus status,
        List<NodeRun> nodeRuns,
        List<ConnectionResolution> connectionResolutions,
        List<WorkflowRunExecutionEdge> executionEdges,
        WorkflowRunGraph runtimeGraph,
        NodeRunOutput result,
        UUID resultSourceNodeRunId,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
    public WorkflowRun(final UUID id,
                       final UUID projectId,
                       final UUID sourceWorkflowId,
                       final UUID taskId,
                       final String workflowName,
                       final String input,
                       final WorkflowRunStatus status,
                       final List<NodeRun> nodeRuns,
                       final Instant createdAt,
                       final Instant startedAt,
                       final Instant finishedAt) {
        this(id, projectId, sourceWorkflowId, taskId, workflowName, input, status, nodeRuns, List.of(), List.of(), null, null, null, createdAt, startedAt, finishedAt);
    }

    public WorkflowRun(final UUID id,
                       final UUID projectId,
                       final UUID sourceWorkflowId,
                       final UUID taskId,
                       final String workflowName,
                       final String input,
                       final WorkflowRunStatus status,
                       final List<NodeRun> nodeRuns,
                       final List<ConnectionResolution> connectionResolutions,
                       final Instant createdAt,
                       final Instant startedAt,
                       final Instant finishedAt) {
        this(id, projectId, sourceWorkflowId, taskId, workflowName, input, status, nodeRuns, connectionResolutions, List.of(), null, null, null, createdAt, startedAt, finishedAt);
    }

    public WorkflowRun(final UUID id,
                       final UUID projectId,
                       final UUID sourceWorkflowId,
                       final UUID taskId,
                       final String workflowName,
                       final String input,
                       final WorkflowRunStatus status,
                       final List<NodeRun> nodeRuns,
                       final List<ConnectionResolution> connectionResolutions,
                       final List<WorkflowRunExecutionEdge> executionEdges,
                       final Instant createdAt,
                       final Instant startedAt,
                       final Instant finishedAt) {
        this(id, projectId, sourceWorkflowId, taskId, workflowName, input, status, nodeRuns, connectionResolutions, executionEdges, null, null, null, createdAt, startedAt, finishedAt);
    }

    public WorkflowRun(final UUID id,
                       final UUID projectId,
                       final UUID sourceWorkflowId,
                       final UUID taskId,
                       final String workflowName,
                       final String input,
                       final WorkflowRunStatus status,
                       final List<NodeRun> nodeRuns,
                       final List<ConnectionResolution> connectionResolutions,
                       final List<WorkflowRunExecutionEdge> executionEdges,
                       final WorkflowRunGraph runtimeGraph,
                       final Instant createdAt,
                       final Instant startedAt,
                       final Instant finishedAt) {
        this(id, projectId, sourceWorkflowId, taskId, workflowName, input, status, nodeRuns, connectionResolutions, executionEdges, runtimeGraph, null, null, createdAt, startedAt, finishedAt);
    }
}
