package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.UUID;

public record SaveAgentLogSourceCommand(
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
