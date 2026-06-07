package com.sitionix.forgeai.domain.usecase;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface GetOperatorUiReadModel {

    OperatorUiTicketListResponse tickets(Integer limit);

    OperatorUiTicketGraphResponse graph(UUID ticketId);

    OperatorUiLaneDetailResponse lane(UUID ticketId, UUID laneId);

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

    record OperatorUiLaneDetailResponse(
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

    record OperatorUiLaneInputTask(
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

    record OperatorUiLaneStep(
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

    record OperatorUiLaneEvent(
            Instant timestamp,
            String eventType,
            String message,
            String stepId,
            Integer stepOrder,
            String turnId,
            String role
    ) {
    }
}
