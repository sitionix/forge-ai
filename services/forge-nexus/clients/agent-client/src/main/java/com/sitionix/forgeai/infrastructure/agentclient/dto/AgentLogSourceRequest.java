package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.sitionix.forgeai.domain.model.agentproxy.AgentLogConnectionType;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogProviderType;
import java.util.UUID;

public record AgentLogSourceRequest(
    String name,
    UUID serviceId,
    AgentLogConnectionType connection,
    UUID sshConnectionId,
    AgentLogProviderType provider,
    String container,
    String composeService,
    String composeFile,
    String unit,
    String path,
    boolean enabled) {}
