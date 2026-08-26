package com.sitionix.forgeagent.api.dto;

import com.sitionix.forgeagent.domain.model.SshAuthType;
import java.time.Instant;
import java.util.UUID;

public record SshConnectionResponse(
    UUID id,
    UUID projectId,
    String name,
    String host,
    int port,
    String username,
    SshAuthType authType,
    Instant createdAt,
    Instant updatedAt) {
    public SshConnectionResponse(
            UUID id,
            UUID projectId,
            String name,
            String host,
            int port,
            String username,
            Instant createdAt,
            Instant updatedAt) {
        this(id, projectId, name, host, port, username, SshAuthType.PRIVATE_KEY, createdAt, updatedAt);
    }
}
