package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

/** A tracked, non-Git project resource. SSH connectivity remains owned by SshConnection. */
public record ProjectAsset(
    UUID id,
    UUID projectId,
    String name,
    UUID sshConnectionId,
    Instant createdAt,
    Instant updatedAt) {}
