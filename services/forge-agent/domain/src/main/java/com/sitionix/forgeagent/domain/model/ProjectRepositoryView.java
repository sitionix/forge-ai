package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record ProjectRepositoryView(
        UUID id,
        UUID projectId,
        String name,
        boolean cloned,
        Instant createdAt
) {
}
