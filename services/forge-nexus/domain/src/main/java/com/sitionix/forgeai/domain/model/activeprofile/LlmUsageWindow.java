package com.sitionix.forgeai.domain.model.activeprofile;

import java.time.Instant;

public record LlmUsageWindow(
        String kind,
        int usedPercent,
        int windowDurationMinutes,
        Instant resetAt
) {
}
