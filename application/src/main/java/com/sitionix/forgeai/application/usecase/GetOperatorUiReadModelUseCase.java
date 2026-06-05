package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorRun;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneDependency;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.repository.LaneExecutionRepository;
import com.sitionix.forgeai.domain.repository.TicketOperatorRunRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel;
import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel.OperatorUiLaneCounts;
import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel.OperatorUiLaneDependency;
import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel.OperatorUiLaneExecution;
import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel.OperatorUiLaneNode;
import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel.OperatorUiTicketGraphResponse;
import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel.OperatorUiTicketListResponse;
import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel.OperatorUiTicketSummary;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetOperatorUiReadModelUseCase implements GetOperatorUiReadModel {

    private static final int DEFAULT_TICKET_LIMIT = 50;
    private static final int MAX_TICKET_LIMIT = 200;

    private final TicketRepository ticketRepository;
    private final LaneExecutionRepository laneExecutionRepository;
    private final TicketOperatorRunRepository ticketOperatorRunRepository;

    @Override
    public OperatorUiTicketListResponse tickets(final Integer limit) {
        return new OperatorUiTicketListResponse(this.ticketRepository.findRecent(this.normalizeLimit(limit)).stream()
                .map(this::asTicketSummary)
                .toList());
    }

    @Override
    public OperatorUiTicketGraphResponse graph(final UUID ticketId) {
        final Ticket ticket = this.ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalStateException("Ticket not found: " + ticketId));
        final List<LaneExecution> executions = this.laneExecutionRepository.findByTicketId(ticketId);
        final Map<UUID, LaneExecution> latestExecutionByLaneId = executions.stream()
                .filter(execution -> execution.getLaneId() != null)
                .collect(Collectors.toMap(
                        LaneExecution::getLaneId,
                        Function.identity(),
                        this::latestExecution
                ));
        final List<Lane> visibleLanes = ticket.getLanes().stream()
                .filter(lane -> this.isVisibleLane(lane, latestExecutionByLaneId.get(lane.getId())))
                .toList();
        final Map<LaneKey, Lane> lanesByKey = visibleLanes.stream()
                .collect(Collectors.toMap(
                        lane -> new LaneKey(this.agentName(lane), lane.getScope()),
                        Function.identity(),
                        (left, right) -> left
                ));
        return new OperatorUiTicketGraphResponse(
                ticket.getId(),
                ticket.getTicketKey(),
                this.name(ticket.getStatus()),
                this.operatorStatus(ticket.getId()),
                ticket.getTaskDescription(),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt(),
                this.counts(visibleLanes),
                visibleLanes.stream()
                        .map(lane -> this.asLaneNode(lane, latestExecutionByLaneId.get(lane.getId()), lanesByKey))
                        .toList()
        );
    }

    private int normalizeLimit(final Integer limit) {
        if (limit == null) {
            return DEFAULT_TICKET_LIMIT;
        }
        return Math.max(1, Math.min(MAX_TICKET_LIMIT, limit));
    }

    private OperatorUiTicketSummary asTicketSummary(final Ticket ticket) {
        return new OperatorUiTicketSummary(
                ticket.getId(),
                ticket.getTicketKey(),
                this.name(ticket.getStatus()),
                this.operatorStatus(ticket.getId()),
                this.taskPreview(ticket.getTaskDescription()),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt(),
                this.counts(ticket.getLanes().stream()
                        .filter(lane -> this.isVisibleLane(lane, null))
                        .toList())
        );
    }

    private OperatorUiLaneNode asLaneNode(final Lane lane,
                                          final LaneExecution execution,
                                          final Map<LaneKey, Lane> lanesByKey) {
        return new OperatorUiLaneNode(
                lane.getId(),
                this.agentName(lane),
                lane.getScope(),
                lane.getServiceId(),
                this.name(lane.getStatus()),
                lane.getAttempt(),
                lane.getInputTaskIds() == null ? 0 : lane.getInputTaskIds().size(),
                this.dependencies(lane, lanesByKey),
                this.execution(execution)
        );
    }

    private List<OperatorUiLaneDependency> dependencies(final Lane lane, final Map<LaneKey, Lane> lanesByKey) {
        if (lane.getDependsOn() == null || lane.getDependsOn().isEmpty()) {
            return List.of();
        }
        return lane.getDependsOn().stream()
                .filter(Objects::nonNull)
                .map(dependency -> this.dependency(dependency, lanesByKey))
                .filter(dependency -> dependency.laneId() != null)
                .toList();
    }

    private OperatorUiLaneDependency dependency(final LaneDependency dependency, final Map<LaneKey, Lane> lanesByKey) {
        final String agent = dependency.getType() == null ? null : dependency.getType().name();
        final Lane lane = lanesByKey.get(new LaneKey(agent, dependency.getScope()));
        return new OperatorUiLaneDependency(
                agent,
                dependency.getScope(),
                lane == null ? null : lane.getId(),
                lane == null ? null : this.name(lane.getStatus())
        );
    }

    private OperatorUiLaneExecution execution(final LaneExecution execution) {
        if (execution == null) {
            return null;
        }
        return new OperatorUiLaneExecution(
                execution.getId(),
                this.name(execution.getStatus()),
                execution.getCurrentStepId(),
                execution.getCurrentStepOrder(),
                execution.getCurrentStepTitle(),
                execution.getLastProgressEvent(),
                execution.getLastProgressAt(),
                execution.getProcessPid(),
                execution.getFailureMessage()
        );
    }

    private LaneExecution latestExecution(final LaneExecution left, final LaneExecution right) {
        return Comparator.comparing(
                        LaneExecution::getStartedAt,
                        Comparator.nullsFirst(LocalDateTime::compareTo)
                )
                .compare(left, right) >= 0 ? left : right;
    }

    private boolean isVisibleLane(final Lane lane, final LaneExecution latestExecution) {
        if (LaneStatus.NOT_NEEDED.equals(lane.getStatus())) {
            return false;
        }
        if (!LaneStatus.NOT_STARTED.equals(lane.getStatus())) {
            return true;
        }
        if (latestExecution != null) {
            return true;
        }
        if (lane.getInputTaskIds() != null && !lane.getInputTaskIds().isEmpty()) {
            return true;
        }
        return lane.getDependsOn() != null && !lane.getDependsOn().isEmpty();
    }

    private OperatorUiLaneCounts counts(final List<Lane> lanes) {
        return new OperatorUiLaneCounts(
                this.count(lanes, LaneStatus.NOT_STARTED),
                this.count(lanes, LaneStatus.READY_TO_START),
                this.count(lanes, LaneStatus.IN_PROGRESS),
                this.count(lanes, LaneStatus.COMPLETED),
                0
        );
    }

    private long count(final List<Lane> lanes, final LaneStatus status) {
        return lanes.stream()
                .filter(lane -> lane.getStatus() == status)
                .count();
    }

    private String operatorStatus(final UUID ticketId) {
        final Optional<TicketOperatorRun> run = this.ticketOperatorRunRepository.findByTicketId(ticketId);
        return run.map(TicketOperatorRun::getStatus)
                .map(Enum::name)
                .orElse(null);
    }

    private String taskPreview(final String taskDescription) {
        if (taskDescription == null || taskDescription.isBlank()) {
            return "";
        }
        return taskDescription.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("");
    }

    private String agentName(final Lane lane) {
        return lane.getAgent() == null ? null : lane.getAgent().name();
    }

    private String name(final Enum<?> value) {
        return value == null ? null : value.name();
    }

    private record LaneKey(String agent, String scope) {
    }
}
