package com.sitionix.forgeai.domain.model.agentproxy;

public record CreateAgentSshConnectionCommand(
    String name,
    String host,
    int port,
    String username,
    AgentSshAuthType authType,
    String privateKeyPath,
    String password) {
  public CreateAgentSshConnectionCommand(
      String name, String host, int port, String username, String privateKeyPath) {
    this(name, host, port, username, AgentSshAuthType.PRIVATE_KEY, privateKeyPath, null);
  }
}
