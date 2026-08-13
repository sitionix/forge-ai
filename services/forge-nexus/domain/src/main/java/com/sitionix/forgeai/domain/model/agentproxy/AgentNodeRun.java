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
        NodeInputMode inputMode,
        NodePosition position,
        AgentNodeRunStatus status,
        AgentNodeRunOutputDocument output,
        AgentNodeRunFailure failure,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
    public AgentNodeRun(final UUID id,
                        final UUID sourceNodeId,
                        final UUID sourceAgentId,
                        final String agentName,
                        final String agentInstructions,
                        final AgentOutputSchemaDocument agentOutputSchema,
                        final List<UUID> dependsOnNodeRunIds,
                        final NodePosition position,
                        final AgentNodeRunStatus status,
                        final AgentNodeRunOutputDocument output,
                        final AgentNodeRunFailure failure,
                        final Instant createdAt,
                        final Instant startedAt,
                        final Instant finishedAt) {
        this(
                id,
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
                createdAt,
                startedAt,
                finishedAt
        );
    }
}
