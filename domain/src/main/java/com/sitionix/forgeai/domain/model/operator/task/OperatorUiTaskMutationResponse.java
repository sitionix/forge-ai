package com.sitionix.forgeai.domain.model.operator.task;

import java.time.LocalDateTime;
import java.util.UUID;

public record OperatorUiTaskMutationResponse(
        UUID ticketId,
        String ticketKey,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
