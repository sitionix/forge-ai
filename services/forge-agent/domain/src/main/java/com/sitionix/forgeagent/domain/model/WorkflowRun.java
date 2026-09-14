package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Objects;

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
        Instant finishedAt,
        List<UUID> repositoryIds,
        OperatorStopStatus operatorStopStatus,
        String operatorStopFailureCode,
        List<UUID> operatorStopPendingNodeRunIds,
        long operatorStopAttempt
) {
    public WorkflowRun {
        repositoryIds = List.copyOf(Objects.requireNonNull(repositoryIds, "repositoryIds must not be null"));
        operatorStopPendingNodeRunIds = List.copyOf(Objects.requireNonNull(
                operatorStopPendingNodeRunIds, "operatorStopPendingNodeRunIds must not be null"));
    }

    public WorkflowRun(
            final UUID id,
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
            final NodeRunOutput result,
            final UUID resultSourceNodeRunId,
            final Instant createdAt,
            final Instant startedAt,
            final Instant finishedAt,
            final List<UUID> repositoryIds
    ) {
        this(id, projectId, sourceWorkflowId, taskId, workflowName, input, status, nodeRuns,
                connectionResolutions, executionEdges, runtimeGraph, result, resultSourceNodeRunId,
                createdAt, startedAt, finishedAt, repositoryIds, null, null, List.of(), 0);
    }
}
