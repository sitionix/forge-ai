package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record ProjectRepositoryView(
        UUID id,
        UUID projectId,
        String name,
        String remoteUrl,
        boolean cloned,
        GitLocalRepositoryState gitState,
        Instant createdAt
) {
}
