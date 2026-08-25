package com.sitionix.forgeai.api.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.*;
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
    AgentLogProviderConfiguration configuration,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt) {}
