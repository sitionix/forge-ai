package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.UUID;

public record RuntimeTargetDiscoveryRequest(String connection, UUID sshConnectionId, String provider) {}
