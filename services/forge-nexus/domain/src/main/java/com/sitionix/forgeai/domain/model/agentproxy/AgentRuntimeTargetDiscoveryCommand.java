package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.UUID;

public record AgentRuntimeTargetDiscoveryCommand(
    String connection, UUID sshConnectionId, String provider) {}
