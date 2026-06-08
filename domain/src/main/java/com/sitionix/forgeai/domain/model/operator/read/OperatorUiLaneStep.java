package com.sitionix.forgeai.domain.model.operator.read;

import java.time.LocalDateTime;

public record OperatorUiLaneStep(
        String stepId,
        int stepOrder,
        String stepTitle,
        String status,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        String resultJson,
        String evidenceJson
) {
}
