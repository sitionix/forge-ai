package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.model.ServiceConnectionType;
import com.sitionix.forgeagent.domain.model.ServiceRuntimeProvider;
import java.util.UUID;

public record RuntimeTargetDiscoveryCommand(
    ServiceConnectionType connection, UUID sshConnectionId, ServiceRuntimeProvider provider) {}
