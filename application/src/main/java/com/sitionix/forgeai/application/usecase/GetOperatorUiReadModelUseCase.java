package com.sitionix.forgeai.application.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.operator.TicketOperatorEventService;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecutionStatus;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorEvent;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorRun;
import com.sitionix.forgeai.domain.model.operator.read.OperatorUiLaneCounts;
import com.sitionix.forgeai.domain.model.operator.read.OperatorUiLaneDependency;
import com.sitionix.forgeai.domain.model.operator.read.OperatorUiLaneDetailResponse;
import com.sitionix.forgeai.domain.model.operator.read.OperatorUiLaneEvent;
import com.sitionix.forgeai.domain.model.operator.read.OperatorUiLaneExecution;
import com.sitionix.forgeai.domain.model.operator.read.OperatorUiLaneInputTask;
import com.sitionix.forgeai.domain.model.operator.read.OperatorUiLaneNode;
import com.sitionix.forgeai.domain.model.operator.read.OperatorUiLaneStep;
import com.sitionix.forgeai.domain.model.operator.read.OperatorUiTicketGraphResponse;
import com.sitionix.forgeai.domain.model.operator.read.OperatorUiTicketListResponse;
import com.sitionix.forgeai.domain.model.operator.read.OperatorUiTicketSummary;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneDependency;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.LaneExecutionRepository;
import com.sitionix.forgeai.domain.repository.LaneStrategyRepository;
import com.sitionix.forgeai.domain.repository.TicketOperatorRunRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel;
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
    private final AgentTicketRepository agentTicketRepository;
    private final LaneStrategyRepository laneStrategyRepository;
    private final TicketOperatorRunRepository ticketOperatorRunRepository;
    private final TicketOperatorEventService ticketOperatorEventService;
    private final ObjectMapper objectMapper;

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
                .filter(this::isVisibleLane)
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

    @Override
    public OperatorUiLaneDetailResponse lane(final UUID ticketId, final UUID laneId) {
        final Ticket ticket = this.ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalStateException("Ticket not found: " + ticketId));
        final Lane lane = ticket.getLanes().stream()
                .filter(value -> Objects.equals(value.getId(), laneId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Lane not found: ticketId=" + ticketId + ", laneId=" + laneId));
        final List<LaneExecution> executions = this.laneExecutionRepository.findByTicketId(ticketId);
        final LaneExecution execution = executions.stream()
                .filter(value -> Objects.equals(value.getLaneId(), laneId))
                .reduce(this::latestExecution)
                .orElse(null);
        final Map<UUID, Lane> lanesById = ticket.getLanes().stream()
                .collect(Collectors.toMap(Lane::getId, Function.identity(), (left, right) -> left));
        final Map<LaneKey, Lane> lanesByKey = ticket.getLanes().stream()
                .collect(Collectors.toMap(
                        value -> new LaneKey(this.agentName(value), value.getScope()),
                        Function.identity(),
                        (left, right) -> left
                ));
        return new OperatorUiLaneDetailResponse(
                ticket.getId(),
                ticket.getTicketKey(),
                this.name(ticket.getStatus()),
                lane.getId(),
                this.agentName(lane),
                lane.getScope(),
                lane.getServiceId(),
                this.name(lane.getStatus()),
                lane.getAttempt(),
                ticket.getTaskDescription(),
                this.dependencies(lane, lanesByKey),
                this.inputTasks(lane, lanesById, lanesByKey),
                this.execution(execution),
                this.steps(lane, execution),
                execution == null || execution.getStderrTail() == null ? List.of() : execution.getStderrTail(),
                this.events(ticketId, laneId)
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
                        .filter(this::isVisibleLane)
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

    private List<OperatorUiLaneInputTask> inputTasks(final Lane lane,
                                                     final Map<UUID, Lane> lanesById,
                                                     final Map<LaneKey, Lane> lanesByKey) {
        if (lane.getInputTaskIds() == null || lane.getInputTaskIds().isEmpty()) {
            return List.of();
        }
        return this.agentTicketRepository.findByIds(lane.getInputTaskIds()).stream()
                .sorted(Comparator.comparing(
                        AgentTicket::getCreatedAt,
                        Comparator.nullsLast(LocalDateTime::compareTo)
                ))
                .map(task -> this.inputTask(lane, task, lanesById, lanesByKey))
                .toList();
    }

    private OperatorUiLaneInputTask inputTask(final Lane targetLane,
                                             final AgentTicket<AgentTicketPayload> task,
                                             final Map<UUID, Lane> lanesById,
                                             final Map<LaneKey, Lane> lanesByKey) {
        final Lane sourceLane = this.sourceLane(targetLane, task, lanesById, lanesByKey);
        return new OperatorUiLaneInputTask(
                task.getId(),
                sourceLane == null ? task.getSourceLaneId() : sourceLane.getId(),
                sourceLane == null ? null : this.agentName(sourceLane),
                sourceLane == null ? null : sourceLane.getScope(),
                this.name(task.getStatus()),
                task.getPayload() == null ? null : task.getPayload().getClass().getSimpleName(),
                this.payloadJson(task.getPayload()),
                task.getCreatedAt()
        );
    }

    private Lane sourceLane(final Lane targetLane,
                            final AgentTicket<AgentTicketPayload> task,
                            final Map<UUID, Lane> lanesById,
                            final Map<LaneKey, Lane> lanesByKey) {
        if (task.getSourceLaneId() != null) {
            return lanesById.get(task.getSourceLaneId());
        }
        if (targetLane.getDependsOn() == null || targetLane.getDependsOn().isEmpty()) {
            return null;
        }
        for (final LaneDependency dependency : targetLane.getDependsOn()) {
            final Lane candidate = lanesByKey.get(new LaneKey(
                    dependency.getType() == null ? null : dependency.getType().name(),
                    dependency.getScope()
            ));
            if (candidate != null && this.payloadMatchesSource(targetLane, dependency, task.getPayload())) {
                return candidate;
            }
        }
        if (targetLane.getDependsOn().size() == 1) {
            final LaneDependency dependency = targetLane.getDependsOn().iterator().next();
            return lanesByKey.get(new LaneKey(
                    dependency.getType() == null ? null : dependency.getType().name(),
                    dependency.getScope()
            ));
        }
        return null;
    }

    private boolean payloadMatchesSource(final Lane targetLane,
                                         final LaneDependency dependency,
                                         final AgentTicketPayload payload) {
        if (targetLane.getAgent() == null || dependency.getType() == null || payload == null) {
            return false;
        }
        try {
            return targetLane.getAgent().inputPayloadTypeFrom(dependency.getType())
                    .filter(type -> type.isInstance(payload))
                    .isPresent();
        } catch (final IllegalStateException ignored) {
            return false;
        }
    }

    private String payloadJson(final AgentTicketPayload payload) {
        if (payload == null) {
            return "{}";
        }
        try {
            return this.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (final JsonProcessingException e) {
            return "{\"serializationError\":\"" + e.getOriginalMessage() + "\"}";
        }
    }

    private List<OperatorUiLaneStep> steps(final Lane lane, final LaneExecution execution) {
        final LaneStrategy strategy = this.laneStrategyRepository.findByAgentId(lane.getAgent().getId());
        final Map<String, LaneStepExecution> persistedSteps = execution == null
                ? Map.of()
                : this.laneExecutionRepository.findStepExecutions(execution.getId()).stream()
                .collect(Collectors.toMap(LaneStepExecution::getStepId, Function.identity(), (left, right) -> right));
        return strategy.getSteps().stream()
                .map(step -> this.step(step, execution, persistedSteps.get(step.getId())))
                .toList();
    }

    private OperatorUiLaneStep step(final LaneStrategyStep step,
                                    final LaneExecution execution,
                                    final LaneStepExecution persistedStep) {
        return new OperatorUiLaneStep(
                step.getId(),
                step.getOrder(),
                step.getTitle(),
                this.stepStatus(step, execution, persistedStep),
                persistedStep == null ? null : persistedStep.getStartedAt(),
                persistedStep == null ? null : persistedStep.getCompletedAt(),
                persistedStep == null ? null : persistedStep.getResultJson(),
                persistedStep == null ? null : persistedStep.getEvidenceJson()
        );
    }

    private String stepStatus(final LaneStrategyStep step,
                              final LaneExecution execution,
                              final LaneStepExecution persistedStep) {
        if (persistedStep != null && persistedStep.isDone()) {
            return "DONE";
        }
        if (execution == null || !Objects.equals(execution.getCurrentStepId(), step.getId())) {
            return "PENDING";
        }
        if (LaneExecutionStatus.FAILED.equals(execution.getStatus())) {
            return "FAILED";
        }
        if (LaneExecutionStatus.INTERRUPTED.equals(execution.getStatus()) || LaneExecutionStatus.CANCELLED.equals(execution.getStatus())) {
            return "INTERRUPTED";
        }
        if (LaneExecutionStatus.COMPLETED.equals(execution.getStatus())) {
            return "DONE";
        }
        return "RUNNING";
    }

    private List<OperatorUiLaneEvent> events(final UUID ticketId, final UUID laneId) {
        return this.ticketOperatorEventService.recentEvents(ticketId).stream()
                .filter(event -> Objects.equals(event.getLaneId(), laneId))
                .map(this::event)
                .toList();
    }

    private OperatorUiLaneEvent event(final TicketOperatorEvent event) {
        return new OperatorUiLaneEvent(
                event.getTimestamp(),
                event.getEventType(),
                event.getMessage(),
                event.getStepId(),
                event.getStepOrder(),
                event.getActiveTurnId(),
                this.eventRole(event)
        );
    }

    private String eventRole(final TicketOperatorEvent event) {
        return switch (Objects.toString(event.getEventType(), "")) {
            case "ORCHESTRATOR_MESSAGE", "CORRECTION_STARTED", "STEP_VALIDATION_FAILED" -> "ORCHESTRATOR";
            case "AGENT_MESSAGE", "AGENT_MESSAGE_DELTA", "TURN_COMPLETED" -> "AGENT";
            case "COMMAND_STARTED", "COMMAND_COMPLETED", "COMMAND_OUTPUT", "FILE_CHANGE" -> "TOOL";
            default -> "SYSTEM";
        };
    }

    private LaneExecution latestExecution(final LaneExecution left, final LaneExecution right) {
        return Comparator.comparing(
                        LaneExecution::getStartedAt,
                        Comparator.nullsFirst(LocalDateTime::compareTo)
                )
                .compare(left, right) >= 0 ? left : right;
    }

    private boolean isVisibleLane(final Lane lane) {
        return !LaneStatus.NOT_NEEDED.equals(lane.getStatus());
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
