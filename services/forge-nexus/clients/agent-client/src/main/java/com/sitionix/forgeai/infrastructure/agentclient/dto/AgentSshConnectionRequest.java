package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.sitionix.forgeai.domain.model.agentproxy.AgentSshAuthType;

public record AgentSshConnectionRequest(
    String name,
    String host,
    int port,
    String username,
    AgentSshAuthType authType,
    String privateKeyPath,
    String password) {
  public AgentSshConnectionRequest(
      String name, String host, int port, String username, String privateKeyPath) {
    this(name, host, port, username, AgentSshAuthType.PRIVATE_KEY, privateKeyPath, null);
  }
}
