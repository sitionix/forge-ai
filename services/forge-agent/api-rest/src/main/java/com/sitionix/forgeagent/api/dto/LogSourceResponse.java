package com.sitionix.forgeagent.api.dto;

import com.sitionix.forgeagent.domain.model.LogConnectionType;
import com.sitionix.forgeagent.domain.model.LogProviderType;
import java.time.Instant;
import java.util.UUID;

public record LogSourceResponse(
    UUID id,
    UUID projectId,
    String name,
    UUID serviceId,
    LogConnectionType connection,
    UUID sshConnectionId,
    LogProviderType provider,
    LogProviderConfigurationResponse configuration,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt) {}
