package com.sitionix.forgeagent.api.dto;

import com.sitionix.forgeagent.domain.model.LogConnectionType;
import com.sitionix.forgeagent.domain.model.LogProviderType;
import java.time.Instant;
import java.util.UUID;

public record LogSourceResponse(
    UUID id,
    UUID projectId,
    String name,
    UUID serviceId,
    UUID assetId,
    LogConnectionType connection,
    UUID sshConnectionId,
    LogProviderType provider,
    LogProviderConfigurationResponse configuration,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt) {
  public LogSourceResponse(UUID id, UUID projectId, String name, UUID serviceId,
      LogConnectionType connection, UUID sshConnectionId, LogProviderType provider,
      LogProviderConfigurationResponse configuration, boolean enabled,
      Instant createdAt, Instant updatedAt) {
    this(id, projectId, name, serviceId, null, connection, sshConnectionId, provider,
        configuration, enabled, createdAt, updatedAt);
  }
}
