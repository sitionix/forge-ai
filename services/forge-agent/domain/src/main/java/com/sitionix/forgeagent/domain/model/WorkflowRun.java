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
        List<UUID> repositoryIds
) {
    public WorkflowRun {
        repositoryIds = List.copyOf(Objects.requireNonNull(repositoryIds, "repositoryIds must not be null"));
    }
}
