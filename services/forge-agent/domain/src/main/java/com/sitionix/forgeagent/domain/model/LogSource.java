package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record LogSource(UUID id, UUID projectId, String name, UUID serviceId,
                        LogConnectionType connectionType, UUID sshConnectionId,
                        LogProviderType provider, LogProviderConfiguration configuration,
                        boolean enabled, Instant createdAt, Instant updatedAt) {
}
