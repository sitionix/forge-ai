package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto;

import java.time.Instant;

public record KnowledgeLlmUsageWindow(
        String kind,
        Integer usedPercent,
        Integer windowDurationMinutes,
        Instant resetAt
) {
    public KnowledgeLlmUsageWindow {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind is required");
        }
        if (usedPercent == null || usedPercent < 0 || usedPercent > 100) {
            throw new IllegalArgumentException("usedPercent must be present and between 0 and 100");
        }
        if (windowDurationMinutes == null || windowDurationMinutes <= 0) {
            throw new IllegalArgumentException("windowDurationMinutes must be present and positive");
        }
        if (resetAt == null) {
            throw new IllegalArgumentException("resetAt is required");
        }
    }
}
