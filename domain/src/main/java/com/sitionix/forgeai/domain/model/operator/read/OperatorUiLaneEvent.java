package com.sitionix.forgeai.domain.model.operator.read;

import java.time.Instant;

public record OperatorUiLaneEvent(
        Instant timestamp,
        String eventType,
        String message,
        String stepId,
        Integer stepOrder,
        String turnId,
        String role
) {
}
