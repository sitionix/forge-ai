package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.time.Instant;
import java.util.UUID;

public record AgentProjectResponse(UUID id, String name, Instant createdAt, Instant updatedAt) {
}
