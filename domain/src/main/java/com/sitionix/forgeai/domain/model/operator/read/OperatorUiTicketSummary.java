package com.sitionix.forgeai.domain.model.operator.read;

import java.time.LocalDateTime;
import java.util.UUID;

public record OperatorUiTicketSummary(
        UUID ticketId,
        String ticketKey,
        String status,
        String operatorStatus,
        String taskPreview,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        OperatorUiLaneCounts laneCounts
) {
}
