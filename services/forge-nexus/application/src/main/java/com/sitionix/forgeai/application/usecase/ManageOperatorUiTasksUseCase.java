package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.application.operator.TicketOperatorEventService;
import com.sitionix.forgeai.domain.exception.TicketNotFoundException;
import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecutionStatus;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorEvent;
import com.sitionix.forgeai.domain.model.operator.task.OperatorUiCreateTaskCommand;
import com.sitionix.forgeai.domain.model.operator.task.OperatorUiServiceCatalogResponse;
import com.sitionix.forgeai.domain.model.operator.task.OperatorUiServiceOption;
import com.sitionix.forgeai.domain.model.operator.task.OperatorUiTaskMutationResponse;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.LaneExecutionRepository;
import com.sitionix.forgeai.domain.repository.TicketOperatorRunRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.props.ServiceConfigView;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.usecase.ManageOperatorUiTasks;
import com.sitionix.forgeai.domain.usecase.ManageTicketOperatorRuns;
import com.sitionix.forgeai.domain.usecase.StartForgeAiTask;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ManageOperatorUiTasksUseCase implements ManageOperatorUiTasks {

    private static final Set<LaneExecutionStatus> RETRYABLE_EXECUTION_STATUSES = Set.of(
            LaneExecutionStatus.FAILED,
            LaneExecutionStatus.INTERRUPTED,
            LaneExecutionStatus.CANCELLED
    );

    private final ServicePropertiesProvider servicePropertiesProvider;
    private final StartForgeAiTask startForgeAiTask;
    private final TicketRepository ticketRepository;
    private final AgentTicketRepository agentTicketRepository;
    private final LaneExecutionRepository laneExecutionRepository;
    private final TicketOperatorRunRepository ticketOperatorRunRepository;
    private final ManageTicketOperatorRuns manageTicketOperatorRuns;
    private final TicketOperatorEventService ticketOperatorEventService;

    @Override
    public OperatorUiServiceCatalogResponse services() {
        final Map<String, ServiceConfigView> services = this.servicePropertiesProvider.getServices();
        if (services == null || services.isEmpty()) {
            return new OperatorUiServiceCatalogResponse(List.of());
        }
        return new OperatorUiServiceCatalogResponse(services.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(this::serviceOption)
                .sorted(Comparator
                        .comparing(OperatorUiServiceOption::group, Comparator.nullsLast(String::compareTo))
                        .thenComparing(OperatorUiServiceOption::label, Comparator.nullsLast(String::compareTo))
                        .thenComparing(OperatorUiServiceOption::id))
                .toList());
    }

    @Override
    public OperatorUiTaskMutationResponse create(final OperatorUiCreateTaskCommand command) {
        return this.response(this.startForgeAiTask.createOpen(this.command(command)));
    }

    @Override
    public OperatorUiTaskMutationResponse execute(final UUID ticketId) {
        if (ticketId == null) {
            throw new IllegalArgumentException("Ticket id is required");
        }
        return this.response(this.startForgeAiTask.executeOpen(ticketId));
    }

    @Override
    public void retryLane(final UUID ticketId, final UUID laneId) {
        if (ticketId == null) {
            throw new IllegalArgumentException("Ticket id is required");
        }
        if (laneId == null) {
            throw new IllegalArgumentException("Lane id is required");
        }
        final Ticket ticket = this.ticketRepository.findById(ticketId)
                .orElseThrow(() -> new TicketNotFoundException(ticketId));
        final Lane lane = ticket.getLanes() == null ? null : ticket.getLanes().stream()
                .filter(value -> Objects.equals(value.getId(), laneId))
                .findFirst()
                .orElse(null);
        if (lane == null) {
            throw new IllegalArgumentException("Lane not found: ticketId=" + ticketId + ", laneId=" + laneId);
        }
        if (Objects.equals(lane.getStatus(), LaneStatus.IN_PROGRESS)) {
            throw new IllegalStateException("Cannot retry active lane: laneId=" + laneId);
        }

        final LaneExecution execution = this.latestLaneExecution(ticketId, laneId);
        if (execution == null) {
            throw new IllegalStateException("Cannot retry lane without previous execution: laneId=" + laneId);
        }
        if (!RETRYABLE_EXECUTION_STATUSES.contains(execution.getStatus())) {
            throw new IllegalStateException("Cannot retry non-failed lane execution: laneId="
                    + laneId + ", status=" + execution.getStatus());
        }

        this.ticketRepository.restartLane(ticketId, laneId);
        this.ticketOperatorEventService.publish(TicketOperatorEvent.builder()
                .ticketId(ticketId)
                .ticketKey(ticket.getTicketKey())
                .laneId(laneId)
                .executionId(execution.getId())
                .agentId(lane.getAgent() == null ? null : lane.getAgent().getId())
                .scope(lane.getScope())
                .stepId(execution.getCurrentStepId())
                .stepTitle(execution.getCurrentStepTitle())
                .stepOrder(execution.getCurrentStepOrder())
                .eventType("LANE_RETRY_REQUESTED")
                .message("Retry requested from operator UI")
                .build());
    }

    @Override
    public void delete(final UUID ticketId) {
        if (ticketId == null) {
            throw new IllegalArgumentException("Ticket id is required");
        }
        this.ticketRepository.findById(ticketId)
                .orElseThrow(() -> new TicketNotFoundException(ticketId));
        if (!this.laneExecutionRepository.findActiveExecutionsByTicketId(ticketId).isEmpty()) {
            this.manageTicketOperatorRuns.interruptTicket(ticketId, "OPERATOR_UI_TICKET_DELETED");
        }
        this.agentTicketRepository.deleteByTicketId(ticketId);
        this.laneExecutionRepository.deleteByTicketId(ticketId);
        this.ticketOperatorRunRepository.deleteByTicketId(ticketId);
        this.ticketRepository.deleteById(ticketId);
        this.ticketOperatorEventService.clear(ticketId);
    }

    private LaneExecution latestLaneExecution(final UUID ticketId, final UUID laneId) {
        return this.laneExecutionRepository.findByTicketId(ticketId).stream()
                .filter(execution -> Objects.equals(execution.getLaneId(), laneId))
                .max(Comparator.comparing(
                        LaneExecution::getUpdatedAt,
                        Comparator.nullsFirst(LocalDateTime::compareTo)
                ))
                .orElse(null);
    }

    private OperatorUiServiceOption serviceOption(final Map.Entry<String, ServiceConfigView> entry) {
        final ServiceConfigView service = entry.getValue();
        return new OperatorUiServiceOption(
                entry.getKey(),
                Objects.toString(service.getLabel(), entry.getKey()),
                service.getPath(),
                service.getGroup() == null ? null : service.getGroup().name(),
                service.getTags() == null ? List.of() : service.getTags()
        );
    }

    private ForgeAiStartCommand command(final OperatorUiCreateTaskCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Create task command is required");
        }
        return ForgeAiStartCommand.builder()
                .ticket(command.ticket())
                .task(command.task())
                .serviceIds(command.serviceIds())
                .sourceTerminalTty(command.sourceTerminalTty())
                .build();
    }

    private OperatorUiTaskMutationResponse response(final Ticket ticket) {
        return new OperatorUiTaskMutationResponse(
                ticket.getId(),
                ticket.getTicketKey(),
                ticket.getStatus() == null ? null : ticket.getStatus().name(),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt()
        );
    }
}
