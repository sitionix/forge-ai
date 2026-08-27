package com.sitionix.forgeai.api.agentproxy;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record AgentRuntimeTargetDiscoveryRequest(
    @NotBlank String connection, UUID sshConnectionId, @NotBlank String provider) {}
