package com.sitionix.forgeai.api.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentLogConnectionType;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogProviderType;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AgentLogDiscoveryRequest(
    @NotNull AgentLogConnectionType connection,
    UUID sshConnectionId,
    @NotNull AgentLogProviderType provider,
    UUID serviceId,
    UUID repositoryId) {}
