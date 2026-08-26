package com.sitionix.forgeai.domain.model.agentproxy;

import java.time.Instant;
import java.util.UUID;

public record AgentSshConnection(
    UUID id,
    UUID projectId,
    String name,
    String host,
    int port,
    String username,
    AgentSshAuthType authType,
    Instant createdAt,
    Instant updatedAt) {
  public AgentSshConnection(
      UUID id,
      UUID projectId,
      String name,
      String host,
      int port,
      String username,
      Instant createdAt,
      Instant updatedAt) {
    this(id, projectId, name, host, port, username, AgentSshAuthType.PRIVATE_KEY, createdAt, updatedAt);
  }
}
