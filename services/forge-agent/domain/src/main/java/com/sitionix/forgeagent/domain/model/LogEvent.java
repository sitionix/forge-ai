package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record LogEvent(UUID sourceId, String sourceName, Instant timestamp, String message) {
}
