package com.sitionix.forgeagent.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ProjectRepositoryResponse(UUID id, UUID projectId, String remoteUrl, Instant createdAt) {
}
