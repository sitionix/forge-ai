package com.sitionix.forgeai.domain.model.agentproxy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentNodeRun(
        UUID id,
        UUID sourceNodeId,
        UUID sourceAgentId,
        String agentName,
        String agentInstructions,
        AgentOutputSchemaDocument agentOutputSchema,
        List<UUID> dependsOnNodeRunIds,
        NodePosition position,
        AgentNodeRunStatus status,
        AgentNodeRunOutputDocument output,
        AgentNodeRunFailure failure,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
}
