package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.sitionix.forgeai.domain.model.agentproxy.AgentLogConnectionType;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogProviderType;
import java.util.UUID;

public record AgentLogDiscoveryRequest(
    AgentLogConnectionType connection,
    UUID sshConnectionId,
    AgentLogProviderType provider,
    UUID repositoryId) {}
