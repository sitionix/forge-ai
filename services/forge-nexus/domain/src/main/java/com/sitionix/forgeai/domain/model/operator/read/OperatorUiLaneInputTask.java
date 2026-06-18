package com.sitionix.forgeai.domain.model.operator.read;

import java.time.LocalDateTime;
import java.util.UUID;

public record OperatorUiLaneInputTask(
        UUID taskId,
        UUID sourceLaneId,
        String sourceAgent,
        String sourceScope,
        String status,
        String payloadType,
        String payloadJson,
        LocalDateTime createdAt
) {
}
