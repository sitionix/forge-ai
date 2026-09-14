package com.sitionix.forgeai.domain.model.agentproxy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentWorkflowRun(
        UUID id,
        UUID projectId,
        UUID sourceWorkflowId,
        UUID taskId,
        String workflowName,
        String input,
        AgentWorkflowRunStatus status,
        List<AgentNodeRun> nodeRuns,
        List<AgentConnectionResolution> connectionResolutions,
        List<AgentWorkflowRunExecutionEdge> executionEdges,
        AgentWorkflowRunGraph runtimeGraph,
        AgentNodeRunOutputDocument result,
        UUID resultSourceNodeRunId,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        List<UUID> repositoryIds,
        AgentOperatorStopStatus operatorStopStatus,
        String operatorStopFailureCode
) {
    public AgentWorkflowRun(
            final UUID id, final UUID projectId, final UUID sourceWorkflowId, final UUID taskId,
            final String workflowName, final String input, final AgentWorkflowRunStatus status,
            final List<AgentNodeRun> nodeRuns,
            final List<AgentConnectionResolution> connectionResolutions,
            final List<AgentWorkflowRunExecutionEdge> executionEdges,
            final AgentWorkflowRunGraph runtimeGraph, final AgentNodeRunOutputDocument result,
            final UUID resultSourceNodeRunId, final Instant createdAt, final Instant startedAt,
            final Instant finishedAt, final List<UUID> repositoryIds
    ) {
        this(id, projectId, sourceWorkflowId, taskId, workflowName, input, status, nodeRuns,
                connectionResolutions, executionEdges, runtimeGraph, result, resultSourceNodeRunId,
                createdAt, startedAt, finishedAt, repositoryIds, null, null);
    }
}
