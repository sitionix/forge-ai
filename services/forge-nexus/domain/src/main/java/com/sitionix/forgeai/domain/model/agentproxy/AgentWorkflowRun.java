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
        List<UUID> repositoryIds
) {
}
