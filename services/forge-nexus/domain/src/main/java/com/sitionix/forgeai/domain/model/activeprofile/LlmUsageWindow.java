package com.sitionix.forgeai.domain.model.activeprofile;

import java.time.Instant;

public record LlmUsageWindow(
        LlmUsageWindowKind kind,
        int usedPercent,
        int windowDurationMinutes,
        Instant resetAt
) {
    public LlmUsageWindow {
        kind = ActiveProfileInvariants.required(kind, "kind");
        if (usedPercent < 0 || usedPercent > 100) {
            throw new IllegalArgumentException("usedPercent must be between 0 and 100");
        }
        if (windowDurationMinutes <= 0) {
            throw new IllegalArgumentException("windowDurationMinutes must be positive");
        }
        resetAt = ActiveProfileInvariants.required(resetAt, "resetAt");
    }
}
