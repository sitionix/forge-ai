package com.sitionix.forgeai.api.agentproxy;

import java.time.Instant;
import java.util.UUID;

public record AgentProjectRepositoryResponse(
        UUID id,
        UUID projectId,
        String name,
        boolean cloned,
        AgentProjectRepositoryGitStateResponse git,
        Instant createdAt
) {
}
