package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.UUID;

public record SaveAgentLogSourceCommand(
    String name,
    UUID serviceId,
    AgentLogConnectionType connection,
    UUID sshConnectionId,
    AgentLogProviderType provider,
    String container,
    String composeService,
    String composeFile,
    AgentSystemdTargetMode systemdMode,
    String unit,
    String path,
    boolean enabled) {
  public SaveAgentLogSourceCommand(
      String name, UUID serviceId, AgentLogConnectionType connection, UUID sshConnectionId,
      AgentLogProviderType provider, String container, String composeService, String composeFile,
      String unit, String path, boolean enabled) {
    this(name, serviceId, connection, sshConnectionId, provider, container, composeService,
        composeFile, AgentSystemdTargetMode.UNIT, unit, path, enabled);
  }
}
