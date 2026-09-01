package com.sitionix.forgeai.infrastructure.agentclient.dto;
import java.time.Instant; import java.util.UUID;
public record ProjectAssetResponse(UUID id, UUID projectId, String name, UUID sshConnectionId, Instant createdAt, Instant updatedAt) {}
