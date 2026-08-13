package com.sitionix.forgeai.api.agentproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentNodeRunResponse(
        UUID id,
        UUID sourceNodeId,
        UUID sourceAgentId,
        String agentName,
        String agentInstructions,
        JsonNode agentOutputSchema,
        List<UUID> dependsOnNodeRunIds,
        String inputMode,
        NodePositionResponse position,
        AgentNodeRunStatus status,
        JsonNode output,
        AgentNodeRunFailureResponse failure,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
    public AgentNodeRunResponse(final UUID id,
                                final UUID sourceNodeId,
                                final UUID sourceAgentId,
                                final String agentName,
                                final String agentInstructions,
                                final JsonNode agentOutputSchema,
                                final List<UUID> dependsOnNodeRunIds,
                                final NodePositionResponse position,
                                final AgentNodeRunStatus status,
                                final JsonNode output,
                                final AgentNodeRunFailureResponse failure,
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
                null,
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
