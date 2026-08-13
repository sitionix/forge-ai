package com.sitionix.forgeagent.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NodeRunResponse(
        UUID id,
        UUID sourceNodeId,
        UUID sourceAgentId,
        String agentName,
        String agentInstructions,
        JsonNode agentOutputSchema,
        List<UUID> dependsOnNodeRunIds,
        String inputMode,
        NodePositionResponse position,
        NodeRunStatus status,
        JsonNode output,
        NodeRunFailureResponse failure,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
    public NodeRunResponse(final UUID id,
                           final UUID sourceNodeId,
                           final UUID sourceAgentId,
                           final String agentName,
                           final String agentInstructions,
                           final JsonNode agentOutputSchema,
                           final List<UUID> dependsOnNodeRunIds,
                           final NodePositionResponse position,
                           final NodeRunStatus status,
                           final JsonNode output,
                           final NodeRunFailureResponse failure,
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
