package com.sitionix.forgeai.domain.model.agentproxy;

import java.time.Instant;
import java.util.UUID;

public record AgentProject(UUID id, String name, Instant createdAt, Instant updatedAt) {
}
