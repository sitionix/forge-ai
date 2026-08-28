package com.sitionix.forgeai.domain.model.agentproxy;

import java.time.Instant;
import java.util.UUID;

public record AgentLogSource(
    UUID id,
    UUID projectId,
    String name,
    UUID serviceId,
    UUID assetId,
    AgentLogConnectionType connection,
    UUID sshConnectionId,
    AgentLogProviderType provider,
    AgentLogProviderConfiguration configuration,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt) {
  public AgentLogSource(UUID id, UUID projectId, String name, UUID serviceId,
      AgentLogConnectionType connection, UUID sshConnectionId, AgentLogProviderType provider,
      AgentLogProviderConfiguration configuration, boolean enabled,
      Instant createdAt, Instant updatedAt) {
    this(id, projectId, name, serviceId, null, connection, sshConnectionId, provider,
        configuration, enabled, createdAt, updatedAt);
  }
}
