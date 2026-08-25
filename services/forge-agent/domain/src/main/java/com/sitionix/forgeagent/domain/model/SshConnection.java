package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Read-safe connection model. Authentication material is deliberately absent. */
public record SshConnection(UUID id, UUID projectId, String name, String host, int port,
                            String username, String privateKeyPath, Instant createdAt, Instant updatedAt) {
    public SshConnection withoutSecretLocation() {
        return new SshConnection(id, projectId, name, host, port, username, null, createdAt, updatedAt);
    }
}
