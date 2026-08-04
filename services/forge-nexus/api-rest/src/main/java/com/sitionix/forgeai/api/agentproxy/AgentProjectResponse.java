package com.sitionix.forgeai.api.agentproxy;

import java.time.Instant;
import java.util.UUID;

public record AgentProjectResponse(UUID id, String name, Instant createdAt, Instant updatedAt) {
}
