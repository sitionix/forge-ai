package com.sitionix.forgeai.domain.model.operator.read;

import java.util.List;
import java.util.UUID;

public record OperatorUiLaneDetailResponse(
        UUID ticketId,
        String ticketKey,
        String ticketStatus,
        UUID laneId,
        String agent,
        String scope,
        String serviceId,
        String status,
        int attempt,
        String taskDescription,
        List<OperatorUiLaneDependency> dependencies,
        List<OperatorUiLaneInputTask> inputTasks,
        OperatorUiLaneExecution execution,
        List<OperatorUiLaneStep> steps,
        List<String> stderrTail,
        List<OperatorUiLaneEvent> events
) {
}
