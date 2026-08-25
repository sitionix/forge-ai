package com.sitionix.forgeai.domain.model.agentproxy;

public record CreateAgentSshConnectionCommand(
    String name, String host, int port, String username, String privateKeyPath) {}
