package com.sitionix.forgeai.domain.model.operator.read;

import java.time.LocalDateTime;
import java.util.UUID;

public record OperatorUiLaneExecution(
        UUID executionId,
        String status,
        String currentStepId,
        Integer currentStepOrder,
        String currentStepTitle,
        String lastProgressEvent,
        LocalDateTime lastProgressAt,
        Long processPid,
        String failureMessage
) {
}
