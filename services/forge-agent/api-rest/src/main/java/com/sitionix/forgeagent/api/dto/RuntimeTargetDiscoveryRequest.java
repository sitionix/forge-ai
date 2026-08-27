package com.sitionix.forgeagent.api.dto;

import com.sitionix.forgeagent.domain.model.ServiceConnectionType;
import com.sitionix.forgeagent.domain.model.ServiceRuntimeProvider;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RuntimeTargetDiscoveryRequest(
    @NotNull ServiceConnectionType connection,
    UUID sshConnectionId,
    @NotNull ServiceRuntimeProvider provider) {}
