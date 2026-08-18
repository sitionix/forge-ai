package com.sitionix.forgeagent.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ProjectRepositoryResponse(UUID id, UUID projectId, String name, boolean cloned, Instant createdAt) {
}
