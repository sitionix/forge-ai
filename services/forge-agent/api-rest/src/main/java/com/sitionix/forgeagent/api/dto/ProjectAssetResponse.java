package com.sitionix.forgeagent.api.dto;
import java.time.Instant;
import java.util.UUID;
public record ProjectAssetResponse(UUID id, UUID projectId, String name, UUID sshConnectionId, Instant createdAt, Instant updatedAt) {}
