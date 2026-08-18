package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.time.Instant;
import java.util.UUID;

public record ProjectRepositoryResponse(
        UUID id,
        UUID projectId,
        String name,
        boolean cloned,
        ProjectRepositoryGitStateResponse gitState,
        Instant createdAt
) {
}
