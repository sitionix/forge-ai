package com.sitionix.forgeagent.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkflowResponse(
        UUID id,
        UUID projectId,
        String name,
        List<NodeResponse> nodes,
        Instant createdAt,
        Instant updatedAt
) {
}
