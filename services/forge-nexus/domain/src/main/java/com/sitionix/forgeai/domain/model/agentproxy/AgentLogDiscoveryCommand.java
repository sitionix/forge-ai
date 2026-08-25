package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.UUID;

public record AgentLogDiscoveryCommand(
    AgentLogConnectionType connection,
    UUID sshConnectionId,
    AgentLogProviderType provider,
    UUID serviceId,
    UUID repositoryId) {}
