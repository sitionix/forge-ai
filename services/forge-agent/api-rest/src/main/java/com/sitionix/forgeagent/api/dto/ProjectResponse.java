package com.sitionix.forgeagent.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(UUID id, String name, Instant createdAt, Instant updatedAt) {
}
