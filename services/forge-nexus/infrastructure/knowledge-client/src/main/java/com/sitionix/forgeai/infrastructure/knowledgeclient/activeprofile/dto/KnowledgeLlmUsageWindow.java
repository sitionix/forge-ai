package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto;

import java.time.Instant;

public record KnowledgeLlmUsageWindow(
        KnowledgeLlmUsageWindowKind kind,
        Integer usedPercent,
        Integer windowDurationMinutes,
        Instant resetAt
) {
}
