package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Read-safe connection model. Authentication material is deliberately absent. */
public record SshConnection(
        UUID id,
        UUID projectId,
        String name,
        String host,
        int port,
        String username,
        SshAuthType authType,
        String privateKeyPath,
        String password,
        Instant createdAt,
        Instant updatedAt) {
    public SshConnection(
            final UUID id,
            final UUID projectId,
            final String name,
            final String host,
            final int port,
            final String username,
            final String privateKeyPath,
            final Instant createdAt,
            final Instant updatedAt) {
        this(id, projectId, name, host, port, username, SshAuthType.PRIVATE_KEY,
                privateKeyPath, null, createdAt, updatedAt);
    }

    public SshConnection withoutSecretLocation() {
        return new SshConnection(
                id, projectId, name, host, port, username, authType, null, null, createdAt, updatedAt);
    }
}
