package com.sitionix.forgeai.domain.model.operator.read;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OperatorUiTicketGraphResponse(
        UUID ticketId,
        String ticketKey,
        String status,
        String operatorStatus,
        String taskDescription,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        OperatorUiLaneCounts laneCounts,
        List<OperatorUiLaneNode> lanes
) {
}
