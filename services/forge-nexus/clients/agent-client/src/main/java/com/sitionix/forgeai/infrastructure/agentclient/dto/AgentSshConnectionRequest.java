package com.sitionix.forgeai.infrastructure.agentclient.dto;

public record AgentSshConnectionRequest(
    String name, String host, int port, String username, String privateKeyPath) {}
