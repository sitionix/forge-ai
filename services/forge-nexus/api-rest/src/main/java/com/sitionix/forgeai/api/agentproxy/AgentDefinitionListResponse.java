package com.sitionix.forgeai.api.agentproxy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentDefinitionListResponse(
        UUID id,
        UUID projectId,
        String name,
        List<AgentDependencyResponse> dependsOn,
        Instant createdAt,
        Instant updatedAt
) {
}
