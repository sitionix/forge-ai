package com.sitionix.forgeagent.domain.model;

import java.util.UUID;

public record ServiceRuntimeTarget(
    ServiceConnectionType connection, UUID sshConnectionId,
    ServiceRuntimeProvider provider, String container, String unit) {
  public String identity() { return provider == ServiceRuntimeProvider.DOCKER ? container : unit; }
}
