package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NodeRun(
        UUID id,
        UUID sourceNodeId,
        UUID sourceAgentId,
        String agentName,
        String agentInstructions,
        AgentOutputSchema agentOutputSchema,
        List<UUID> dependsOnNodeRunIds,
        NodePosition position,
        NodeRunStatus status,
        NodeRunOutput output,
        NodeRunFailure failure,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
}
