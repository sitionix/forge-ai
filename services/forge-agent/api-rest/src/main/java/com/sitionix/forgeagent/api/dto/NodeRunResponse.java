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
        NodePositionResponse position,
        NodeRunStatus status,
        JsonNode output,
        NodeRunFailureResponse failure,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
}
