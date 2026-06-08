package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.OperatorExecutionDTO;
import com.app_afesox.fgaisox.api_first.dto.OperatorExecutionsResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.OperatorUiCreateTaskRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.OperatorUiLaneDetailResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.OperatorUiServiceCatalogResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.OperatorUiTaskMutationResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.OperatorUiTicketGraphResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.OperatorUiTicketListResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.TicketOperatorEventDTO;
import com.app_afesox.fgaisox.api_first.dto.TicketOperatorExecutionDTO;
import com.app_afesox.fgaisox.api_first.dto.TicketOperatorLaneSummaryDTO;
import com.app_afesox.fgaisox.api_first.dto.TicketOperatorRunDTO;
import com.app_afesox.fgaisox.api_first.dto.TicketOperatorRunsResponseDTO;
import com.app_afesox.fgaisox.api_first.dto.TicketOperatorSnapshotResponseDTO;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorEvent;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorRun;
import com.sitionix.forgeai.domain.model.operator.read.OperatorUiLaneDetailResponse;
import com.sitionix.forgeai.domain.model.operator.read.OperatorUiTicketGraphResponse;
import com.sitionix.forgeai.domain.model.operator.read.OperatorUiTicketListResponse;
import com.sitionix.forgeai.domain.model.operator.task.OperatorUiCreateTaskCommand;
import com.sitionix.forgeai.domain.model.operator.task.OperatorUiServiceCatalogResponse;
import com.sitionix.forgeai.domain.model.operator.task.OperatorUiTaskMutationResponse;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public abstract class ForgeAiOperatorApiMapper {

    @Mapping(target = "executionId", source = "id")
    @Mapping(target = "status", expression = "java(source.getStatus() == null ? null : source.getStatus().name())")
    @Mapping(target = "codexSessionId", source = "sessionId")
    @Mapping(target = "codexThreadId", source = "threadId")
    @Mapping(target = "activeStepId", source = "currentStepId")
    @Mapping(target = "activeStepOrder", source = "currentStepOrder")
    @Mapping(target = "activeStepTitle", source = "currentStepTitle")
    @Mapping(target = "lastProgressAt", expression = "java(toOffsetDateTime(source.getLastProgressAt()))")
    @Mapping(target = "stopCommand", ignore = true)
    public abstract OperatorExecutionDTO asOperatorExecution(LaneExecution source);

    public OperatorExecutionsResponseDTO asOperatorExecutionsResponse(final List<OperatorExecutionDTO> items) {
        return OperatorExecutionsResponseDTO.builder()
                .items(items)
                .build();
    }

    @Mapping(target = "status", expression = "java(source.getStatus() == null ? null : source.getStatus().name())")
    @Mapping(target = "lastHeartbeatAt", expression = "java(toOffsetDateTime(source.getLastHeartbeatAt()))")
    @Mapping(target = "cancelRequestedAt", expression = "java(toOffsetDateTime(source.getCancelRequestedAt()))")
    @Mapping(target = "cancelledAt", expression = "java(toOffsetDateTime(source.getCancelledAt()))")
    @Mapping(target = "lastProgressAt", expression = "java(toOffsetDateTime(source.getLastProgressAt()))")
    public abstract TicketOperatorRunDTO asTicketOperatorRun(TicketOperatorRun source);

    public TicketOperatorRunsResponseDTO asTicketOperatorRunsResponse(final List<TicketOperatorRunDTO> items) {
        return TicketOperatorRunsResponseDTO.builder()
                .items(items)
                .build();
    }

    @Mapping(target = "executionId", source = "id")
    @Mapping(target = "status", expression = "java(source.getStatus() == null ? null : source.getStatus().name())")
    @Mapping(target = "codexSessionId", source = "sessionId")
    @Mapping(target = "codexThreadId", source = "threadId")
    @Mapping(target = "activeStepId", source = "currentStepId")
    @Mapping(target = "activeStepOrder", source = "currentStepOrder")
    @Mapping(target = "activeStepTitle", source = "currentStepTitle")
    public abstract TicketOperatorExecutionDTO asTicketOperatorExecution(LaneExecution source);

    @Mapping(target = "lastHeartbeatAt", expression = "java(toOffsetDateTime(source.getLastHeartbeatAt()))")
    @Mapping(target = "cancelRequestedAt", expression = "java(toOffsetDateTime(source.getCancelRequestedAt()))")
    @Mapping(target = "cancelledAt", expression = "java(toOffsetDateTime(source.getCancelledAt()))")
    @Mapping(target = "lastProgressAt", expression = "java(toOffsetDateTime(source.getLastProgressAt()))")
    @Mapping(target = "status", expression = "java(source.getStatus() == null ? null : source.getStatus().name())")
    public abstract TicketOperatorRunDTO asTicketOperatorRunForSnapshot(TicketOperatorRun source);

    public TicketOperatorLaneSummaryDTO asTicketOperatorLaneSummary(final Ticket ticket) {
        return TicketOperatorLaneSummaryDTO.builder()
                .completed(countByStatus(ticket, LaneStatus.COMPLETED))
                .inProgress(countByStatus(ticket, LaneStatus.IN_PROGRESS))
                .ready(countByStatus(ticket, LaneStatus.READY_TO_START))
                .notStarted(countByStatus(ticket, LaneStatus.NOT_STARTED))
                .notNeeded(countByStatus(ticket, LaneStatus.NOT_NEEDED))
                .build();
    }

    @Mapping(target = "codexProcessPid", source = "codexProcessPid")
    @Mapping(target = "codexSessionId", source = "codexSessionId")
    @Mapping(target = "codexThreadId", source = "codexThreadId")
    @Mapping(target = "timestamp", expression = "java(toOffsetDateTime(source.getTimestamp()))")
    public abstract TicketOperatorEventDTO asTicketOperatorEvent(TicketOperatorEvent source);

    public TicketOperatorSnapshotResponseDTO asTicketOperatorSnapshot(
            final TicketOperatorRunDTO run,
            final TicketOperatorLaneSummaryDTO laneSummary,
            final List<TicketOperatorExecutionDTO> activeExecutions,
            final List<TicketOperatorEventDTO> recentEvents
    ) {
        return TicketOperatorSnapshotResponseDTO.builder()
                .run(run)
                .laneSummary(laneSummary)
                .activeExecutions(activeExecutions)
                .recentEvents(recentEvents)
                .build();
    }

    public abstract OperatorUiTicketListResponseDTO asOperatorUiTicketListResponse(
            OperatorUiTicketListResponse source
    );

    public abstract OperatorUiTicketGraphResponseDTO asOperatorUiTicketGraphResponse(
            OperatorUiTicketGraphResponse source
    );

    public abstract OperatorUiLaneDetailResponseDTO asOperatorUiLaneDetailResponse(
            OperatorUiLaneDetailResponse source
    );

    public abstract OperatorUiServiceCatalogResponseDTO asOperatorUiServiceCatalogResponse(
            OperatorUiServiceCatalogResponse source
    );

    public abstract OperatorUiTaskMutationResponseDTO asOperatorUiTaskMutationResponse(
            OperatorUiTaskMutationResponse source
    );

    public abstract OperatorUiCreateTaskCommand asOperatorUiCreateTaskCommand(
            OperatorUiCreateTaskRequestDTO source
    );

    public OffsetDateTime toOffsetDateTime(final LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    public OffsetDateTime toOffsetDateTime(final Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private long countByStatus(final Ticket ticket, final LaneStatus status) {
        return ticket.getLanes().stream()
                .filter(lane -> lane.getStatus() == status)
                .count();
    }
}
