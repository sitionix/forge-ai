package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = false)
public record KnowledgeLlmUsageWindow(
        KnowledgeLlmUsageWindowKind kind,
        int usedPercent,
        int windowDurationMinutes,
        Instant resetAt
) {
}
