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
        Instant routingCompletedAt,
        NodeRunStatus status,
        NodeRunOutput output,
        NodeRunFailure failure,
        NodeRunExecutionModel executionModel,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        UUID repositoryId
) {
}
