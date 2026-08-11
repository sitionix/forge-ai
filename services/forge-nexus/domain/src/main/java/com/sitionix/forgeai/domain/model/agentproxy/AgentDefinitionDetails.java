package com.sitionix.forgeai.domain.model.agentproxy;

import java.time.Instant;
import java.util.UUID;

public record AgentDefinitionDetails(
        UUID id,
        UUID projectId,
        String name,
        String instructions,
        AgentOutputSchemaDocument outputSchema,
        AgentModelSelection model,
        Instant createdAt,
        Instant updatedAt
) {
}
