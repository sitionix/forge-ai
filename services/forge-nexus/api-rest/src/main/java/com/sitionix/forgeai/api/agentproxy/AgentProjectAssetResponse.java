package com.sitionix.forgeai.api.agentproxy;

import java.time.Instant;
import java.util.UUID;

public record AgentProjectAssetResponse(
        UUID id,
        UUID projectId,
        String name,
        UUID sshConnectionId,
        Instant createdAt,
        Instant updatedAt) {
}
