package com.sitionix.forgeai.api.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentSystemdTargetMode;

import com.sitionix.forgeai.domain.model.agentproxy.AgentLogConnectionType;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogProviderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AgentLogSourceRequest(
    @NotBlank String name,
    UUID serviceId,
    @NotNull AgentLogConnectionType connection,
    UUID sshConnectionId,
    @NotNull AgentLogProviderType provider,
    String container,
    String composeService,
    String composeFile,
    AgentSystemdTargetMode systemdMode,
    String unit,
    String path,
    boolean enabled) {
  public AgentLogSourceRequest(
      String name, UUID serviceId, AgentLogConnectionType connection, UUID sshConnectionId,
      AgentLogProviderType provider, String container, String composeService, String composeFile,
      String unit, String path, boolean enabled) {
    this(name, serviceId, connection, sshConnectionId, provider, container, composeService,
        composeFile, AgentSystemdTargetMode.UNIT, unit, path, enabled);
  }
}
