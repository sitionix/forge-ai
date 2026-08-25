package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.time.Instant;
import java.util.UUID;

public record AgentSshConnectionResponse(
    UUID id,
    UUID projectId,
    String name,
    String host,
    int port,
    String username,
    Instant createdAt,
    Instant updatedAt) {}
