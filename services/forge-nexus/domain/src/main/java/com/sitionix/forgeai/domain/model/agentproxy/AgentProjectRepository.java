package com.sitionix.forgeai.domain.model.agentproxy;

import java.time.Instant;
import java.util.UUID;

public record AgentProjectRepository(
        UUID id,
        UUID projectId,
        String name,
        String remoteUrl,
        boolean cloned,
        AgentProjectRepositoryGitState git,
        Instant createdAt
) {
}
