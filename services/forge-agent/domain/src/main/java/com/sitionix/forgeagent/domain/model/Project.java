package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record Project(
        UUID id,
        String name,
        String normalizedName,
        Instant createdAt,
        Instant updatedAt
) {
}
