package com.sitionix.forgeagent.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentListResponse(
        UUID id,
        UUID projectId,
        String name,
        List<AgentDependencyResponse> dependsOn,
        Instant createdAt,
        Instant updatedAt
) {
}
