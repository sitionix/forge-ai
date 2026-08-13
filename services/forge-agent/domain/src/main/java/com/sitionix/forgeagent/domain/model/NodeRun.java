package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NodeRun(
        UUID id,
        UUID workflowRunId,
        UUID sourceNodeId,
        UUID sourceAgentId,
        String agentName,
        String agentInstructions,
        AgentOutputSchema agentOutputSchema,
        List<UUID> dependsOnNodeRunIds,
        NodeInputMode inputMode,
        NodePosition position,
        NodeRunStatus status,
        NodeRunOutput output,
        NodeRunFailure failure,
        NodeRunExecutionModel executionModel,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
    public NodeRun(final UUID id,
                   final UUID workflowRunId,
                   final UUID sourceNodeId,
                   final UUID sourceAgentId,
                   final String agentName,
                   final String agentInstructions,
                   final AgentOutputSchema agentOutputSchema,
                   final List<UUID> dependsOnNodeRunIds,
                   final NodePosition position,
                   final NodeRunStatus status,
                   final NodeRunOutput output,
                   final NodeRunFailure failure,
                   final NodeRunExecutionModel executionModel,
                   final Instant createdAt,
                   final Instant startedAt,
                   final Instant finishedAt) {
        this(
                id,
                workflowRunId,
                sourceNodeId,
                sourceAgentId,
                agentName,
                agentInstructions,
                agentOutputSchema,
                dependsOnNodeRunIds,
                NodeInputMode.DEPENDENCIES_ONLY,
                position,
                status,
                output,
                failure,
                executionModel,
                createdAt,
                startedAt,
                finishedAt
        );
    }
}
