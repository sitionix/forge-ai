package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record ProjectTask(
        UUID id,
        UUID projectId,
        String title,
        String input,
        UUID workflowId,
        Instant createdAt,
        Instant updatedAt
) {
}
