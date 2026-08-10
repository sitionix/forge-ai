package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunStatus;
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
        AgentNodeRunStatus status,
        JsonNode output,
        NodeRunFailureResponse failure,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
}
