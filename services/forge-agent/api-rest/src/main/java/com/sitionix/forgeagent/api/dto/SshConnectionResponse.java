package com.sitionix.forgeagent.api.dto;

import java.time.Instant;
import java.util.UUID;

public record SshConnectionResponse(
    UUID id,
    UUID projectId,
    String name,
    String host,
    int port,
    String username,
    Instant createdAt,
    Instant updatedAt) {}
