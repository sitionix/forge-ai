package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record ProjectService(UUID id, UUID projectId, String name, UUID repositoryId,
    ServiceRuntimeTarget runtimeTarget, Instant createdAt, Instant updatedAt) {}
