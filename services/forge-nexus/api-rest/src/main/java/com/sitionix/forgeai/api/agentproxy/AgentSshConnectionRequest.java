package com.sitionix.forgeai.api.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentSshAuthType;
import jakarta.validation.constraints.*;

public record AgentSshConnectionRequest(
    @NotBlank String name,
    @NotBlank String host,
    @Min(1) @Max(65535) int port,
    @NotBlank String username,
    @NotNull AgentSshAuthType authType,
    String privateKeyPath,
    String password) {
  public AgentSshConnectionRequest(
      String name, String host, int port, String username, String privateKeyPath) {
    this(name, host, port, username, AgentSshAuthType.PRIVATE_KEY, privateKeyPath, null);
  }
}
