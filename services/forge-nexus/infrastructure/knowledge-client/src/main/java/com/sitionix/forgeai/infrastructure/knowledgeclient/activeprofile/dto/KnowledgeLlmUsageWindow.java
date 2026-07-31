package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto;

import java.time.Instant;

public record KnowledgeLlmUsageWindow(
        String kind,
        Integer usedPercent,
        Integer windowDurationMinutes,
        Instant resetAt
) {
}
