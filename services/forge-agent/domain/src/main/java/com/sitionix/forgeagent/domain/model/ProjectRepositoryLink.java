package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record ProjectRepositoryLink(
        UUID id,
        UUID projectId,
        String remoteUrl,
        Instant createdAt
) {
}
