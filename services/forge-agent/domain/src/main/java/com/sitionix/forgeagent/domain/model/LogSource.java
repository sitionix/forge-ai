package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record LogSource(UUID id, UUID projectId, String name, LogSourceOwnerType ownerType,
                        UUID serviceId, UUID assetId,
                        LogConnectionType connectionType, UUID sshConnectionId,
                        LogProviderType provider, LogProviderConfiguration configuration,
                        boolean enabled, Instant createdAt, Instant updatedAt) {
  public LogSource {
    if (ownerType == null) ownerType = inferOwnerType(serviceId, assetId);
  }

  public LogSource(UUID id, UUID projectId, String name, UUID serviceId, UUID assetId,
                   LogConnectionType connectionType, UUID sshConnectionId,
                   LogProviderType provider, LogProviderConfiguration configuration,
                   boolean enabled, Instant createdAt, Instant updatedAt) {
    this(id, projectId, name, inferOwnerType(serviceId, assetId), serviceId, assetId,
        connectionType, sshConnectionId, provider, configuration, enabled, createdAt, updatedAt);
  }

  public LogSource(UUID id, UUID projectId, String name, UUID serviceId,
                   LogConnectionType connectionType, UUID sshConnectionId,
                   LogProviderType provider, LogProviderConfiguration configuration,
                   boolean enabled, Instant createdAt, Instant updatedAt) {
    this(id, projectId, name, serviceId, null, connectionType, sshConnectionId,
        provider, configuration, enabled, createdAt, updatedAt);
  }

  private static LogSourceOwnerType inferOwnerType(UUID serviceId, UUID assetId) {
    if (serviceId != null) return LogSourceOwnerType.LEGACY_SERVICE;
    if (assetId != null) return LogSourceOwnerType.ASSET;
    return LogSourceOwnerType.CUSTOM;
  }
}
