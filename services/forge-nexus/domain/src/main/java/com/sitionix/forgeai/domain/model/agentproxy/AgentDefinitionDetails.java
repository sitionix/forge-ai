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
    public AgentDefinitionDetails(final UUID id,
                                  final UUID projectId,
                                  final String name,
                                  final String instructions,
                                  final AgentOutputSchemaDocument outputSchema,
                                  final Instant createdAt,
                                  final Instant updatedAt) {
        this(id, projectId, name, instructions, outputSchema, null, createdAt, updatedAt);
    }
}
