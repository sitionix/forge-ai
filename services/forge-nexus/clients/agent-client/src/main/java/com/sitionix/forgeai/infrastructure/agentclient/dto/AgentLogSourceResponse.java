package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.sitionix.forgeai.domain.model.agentproxy.AgentLogConnectionType;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogProviderType;
import java.time.Instant;
import java.util.UUID;

public record AgentLogSourceResponse(
    UUID id,
    UUID projectId,
    String name,
    UUID serviceId,
    AgentLogConnectionType connection,
    UUID sshConnectionId,
    AgentLogProviderType provider,
    AgentLogConfigurationResponse configuration,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt) {}
