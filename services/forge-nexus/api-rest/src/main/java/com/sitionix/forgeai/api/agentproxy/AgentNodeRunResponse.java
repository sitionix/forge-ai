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
        NodePositionResponse position,
        AgentNodeRunStatus status,
        JsonNode output,
        AgentNodeRunFailureResponse failure,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
}
