package com.sitionix.forgeai.api.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.*;
import java.time.Instant;
import java.util.UUID;

public record AgentLogSourceResponse(
    UUID id,
    UUID projectId,
    String name,
    UUID serviceId,
    UUID assetId,
    AgentLogConnectionType connection,
    UUID sshConnectionId,
    AgentLogProviderType provider,
    AgentLogConfigurationResponse configuration,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt) {
  public AgentLogSourceResponse(UUID id, UUID projectId, String name, UUID serviceId,
      AgentLogConnectionType connection, UUID sshConnectionId, AgentLogProviderType provider,
      AgentLogConfigurationResponse configuration, boolean enabled,
      Instant createdAt, Instant updatedAt) {
    this(id, projectId, name, serviceId, null, connection, sshConnectionId, provider,
        configuration, enabled, createdAt, updatedAt);
  }
}
