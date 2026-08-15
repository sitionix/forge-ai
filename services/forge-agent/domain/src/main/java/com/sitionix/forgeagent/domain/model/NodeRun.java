package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record NodeRun(
        UUID id,
        UUID workflowRunId,
        UUID sourceNodeId,
        UUID sourceAgentId,
        String agentName,
        String agentInstructions,
        AgentOutputSchema agentOutputSchema,
        NodeInputMode inputMode,
        NodePosition position,
        UUID executionFrameId,
        UUID enteredViaInputPortId,
        UUID activationFrameId,
        UUID selectedOutputPortId,
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
                   final UUID executionFrameId,
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
                NodeInputMode.DEPENDENCIES_ONLY,
                position,
                executionFrameId,
                null,
                null,
                null,
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
