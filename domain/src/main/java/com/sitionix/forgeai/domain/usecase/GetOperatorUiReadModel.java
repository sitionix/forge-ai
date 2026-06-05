package com.sitionix.forgeai.domain.usecase;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface GetOperatorUiReadModel {

    OperatorUiTicketListResponse tickets(Integer limit);

    OperatorUiTicketGraphResponse graph(UUID ticketId);

    record OperatorUiTicketListResponse(List<OperatorUiTicketSummary> tickets) {
    }

    record OperatorUiTicketSummary(
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

    record OperatorUiTicketGraphResponse(
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

    record OperatorUiLaneCounts(
            long notStarted,
            long ready,
            long inProgress,
            long completed,
            long notNeeded
    ) {
    }

    record OperatorUiLaneNode(
            UUID laneId,
            String agent,
            String scope,
            String serviceId,
            String status,
            int attempt,
            int inputTaskCount,
            List<OperatorUiLaneDependency> dependencies,
            OperatorUiLaneExecution execution
    ) {
    }

    record OperatorUiLaneDependency(
            String agent,
            String scope,
            UUID laneId,
            String status
    ) {
    }

    record OperatorUiLaneExecution(
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
}
