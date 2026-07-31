package com.sitionix.forgeai.api.activeprofile;

import java.time.Instant;

public record LlmUsageWindowResponse(
        String kind,
        int usedPercent,
        int windowDurationMinutes,
        Instant resetAt
) {
}
