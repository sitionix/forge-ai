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
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
    public AgentWorkflowRun(final UUID id,
                            final UUID projectId,
                            final UUID sourceWorkflowId,
                            final UUID taskId,
                            final String workflowName,
                            final String input,
                            final AgentWorkflowRunStatus status,
                            final List<AgentNodeRun> nodeRuns,
                            final Instant createdAt,
                            final Instant startedAt,
                            final Instant finishedAt) {
        this(id, projectId, sourceWorkflowId, taskId, workflowName, input, status, nodeRuns, List.of(), createdAt, startedAt, finishedAt);
    }
}
