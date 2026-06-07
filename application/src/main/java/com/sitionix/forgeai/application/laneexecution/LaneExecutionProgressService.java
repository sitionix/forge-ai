package com.sitionix.forgeai.application.laneexecution;

import com.sitionix.forgeai.application.operator.TicketOperatorRunService;
import com.sitionix.forgeai.domain.model.codex.CodexProgressEvent;
import com.sitionix.forgeai.domain.model.codex.CodexProgressEventType;
import com.sitionix.forgeai.domain.model.codex.CodexSession;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecutionStatus;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorEvent;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorRun;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.CodexProgressObserver;
import com.sitionix.forgeai.domain.repository.LaneExecutionRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LaneExecutionProgressService implements CodexProgressObserver {

    private static final int STDERR_TAIL_LIMIT = 200;
    private static final Logger progressLog = Logger.getLogger("com.sitionix.forgeai.codex.progress");

    private final LaneExecutionRepository laneExecutionRepository;
    private final TicketOperatorRunService ticketOperatorRunService;

    public LaneExecution createStartingExecution(final ReadyToStartLane lane, final LaneStrategy strategy, final UUID executionId) {
        final LocalDateTime now = LocalDateTime.now();
        final LaneStrategyStep firstStep = strategy.getSteps().getFirst();
        final LaneExecution execution = LaneExecution.builder()
                .id(executionId)
                .ticketId(lane.getTicketId())
                .laneId(lane.getLaneId())
                .agentId(lane.getAgent().getId())
                .scope(lane.getScope())
                .strategyId(strategy.getAgentId())
                .strategyVersion(strategy.getVersion())
                .status(LaneExecutionStatus.STARTING)
                .currentStepId(firstStep.getId())
                .currentStepOrder(firstStep.getOrder())
                .currentStepTitle(firstStep.getTitle())
                .startedAt(now)
                .updatedAt(now)
                .build();
        final LaneExecution saved = this.laneExecutionRepository.saveExecution(execution);
        this.ticketOperatorRunService.markExecutionStarted(saved);
        this.ticketOperatorRunService.publishEvent(TicketOperatorEvent.builder()
                .ticketId(saved.getTicketId())
                .ticketKey(lane.getTicketKey())
                .laneId(saved.getLaneId())
                .executionId(saved.getId())
                .agentId(saved.getAgentId())
                .scope(saved.getScope())
                .eventType("LANE_STARTED")
                .message("Lane started")
                .timestamp(Instant.now())
                .build());
        return saved;
    }

    public LaneExecution markSessionStarted(final UUID executionId, final CodexSession session) {
        return this.updateExecution(executionId, execution -> execution.toBuilder()
                .status(LaneExecutionStatus.SESSION_STARTED)
                .sessionId(session.id())
                .threadId(session.threadId())
                .processPid(session.processPid())
                .processCommand(session.command() == null ? null : String.join(" ", session.command()))
                .processCwd(session.cwd())
                .codexVersion(session.codexVersion())
                .processStartedAt(session.startedAt() == null ? null : LocalDateTime.ofInstant(session.startedAt(), ZoneOffset.UTC))
                .updatedAt(LocalDateTime.now())
                .build());
    }

    public LaneExecution markStepStarted(final UUID executionId, final LaneStrategyStep step) {
        return this.updateExecution(executionId, execution -> execution.toBuilder()
                .status(LaneExecutionStatus.STEP_RUNNING)
                .currentStepId(step.getId())
                .currentStepOrder(step.getOrder())
                .currentStepTitle(step.getTitle())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    public LaneExecution markWaitingForCodex(final UUID executionId) {
        return this.updateStatus(executionId, LaneExecutionStatus.WAITING_FOR_CODEX);
    }

    public LaneExecution markValidatingResponse(final UUID executionId) {
        return this.updateStatus(executionId, LaneExecutionStatus.VALIDATING_RESPONSE);
    }

    public LaneExecution markPersistingStep(final UUID executionId) {
        return this.updateStatus(executionId, LaneExecutionStatus.PERSISTING_STEP);
    }

    public LaneExecution markCorrectionRunning(final UUID executionId) {
        return this.updateStatus(executionId, LaneExecutionStatus.CORRECTION_RUNNING);
    }

    public LaneExecution markCompletingLane(final UUID executionId) {
        return this.updateStatus(executionId, LaneExecutionStatus.COMPLETING_LANE);
    }

    public LaneExecution markCompleted(final UUID executionId) {
        final LaneExecution updated = this.updateExecution(executionId, execution -> execution.toBuilder()
                .status(LaneExecutionStatus.COMPLETED)
                .completedAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        this.ticketOperatorRunService.markExecutionFinished(updated);
        return updated;
    }

    public LaneExecution markCancelRequested(final UUID executionId) {
        return this.updateExecution(executionId, execution -> execution.toBuilder()
                .status(LaneExecutionStatus.CANCEL_REQUESTED)
                .cancelRequestedAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    public LaneExecution markInterrupted(final UUID executionId, final String message) {
        final LaneExecution updated = this.updateExecution(executionId, execution -> execution.toBuilder()
                .status(LaneExecutionStatus.INTERRUPTED)
                .failureMessage(message)
                .interruptedAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        this.ticketOperatorRunService.markExecutionFinished(updated);
        return updated;
    }

    public LaneExecution markCancelled(final UUID executionId, final String message) {
        final LaneExecution updated = this.updateExecution(executionId, execution -> execution.toBuilder()
                .status(LaneExecutionStatus.CANCELLED)
                .failureMessage(message)
                .interruptedAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        this.ticketOperatorRunService.markExecutionFinished(updated);
        return updated;
    }

    public LaneExecution markFailed(final UUID executionId, final String message) {
        final LaneExecution updated = this.updateExecution(executionId, execution -> execution.toBuilder()
                .status(LaneExecutionStatus.FAILED)
                .failureMessage(message)
                .failedAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        this.ticketOperatorRunService.markExecutionFinished(updated);
        return updated;
    }

    public LaneExecution getExecution(final UUID executionId) {
        return this.laneExecutionRepository.findExecution(executionId)
                .orElseThrow(() -> new IllegalStateException("Unknown lane executionId=" + executionId));
    }

    public List<LaneExecution> findActiveExecutions() {
        return this.laneExecutionRepository.findActiveExecutions();
    }

    public List<LaneExecution> findActiveExecutionsByTicket(final UUID ticketId) {
        return this.laneExecutionRepository.findActiveExecutionsByTicketId(ticketId);
    }

    public void publishStepStarted(final ReadyToStartLane lane, final UUID executionId, final LaneStrategyStep step, final int totalSteps) {
        final LaneExecution execution = this.getExecution(executionId);
        this.ticketOperatorRunService.publishEvent(TicketOperatorEvent.builder()
                .ticketId(lane.getTicketId())
                .ticketKey(lane.getTicketKey())
                .laneId(lane.getLaneId())
                .executionId(executionId)
                .agentId(lane.getAgent().getId())
                .scope(lane.getScope())
                .stepId(step.getId())
                .stepTitle(step.getTitle())
                .stepOrder(step.getOrder())
                .totalSteps(totalSteps)
                .codexProcessPid(execution.getProcessPid())
                .codexSessionId(execution.getSessionId())
                .codexThreadId(execution.getThreadId())
                .activeTurnId(execution.getActiveTurnId())
                .eventType("STEP_STARTED")
                .message("Step started")
                .timestamp(Instant.now())
                .build());
    }

    public void publishStepValidationPassed(final ReadyToStartLane lane, final UUID executionId, final LaneStrategyStep step, final int totalSteps) {
        this.publishSimple(lane, executionId, step, totalSteps, "STEP_VALIDATION_PASSED", "Step validation passed");
    }

    public void publishStepPersisted(final ReadyToStartLane lane, final UUID executionId, final LaneStrategyStep step, final int totalSteps) {
        this.publishSimple(lane, executionId, step, totalSteps, "STEP_PERSISTED", "Step persisted");
    }

    public void publishNextStep(final ReadyToStartLane lane, final UUID executionId, final LaneStrategyStep from, final LaneStrategyStep to) {
        this.ticketOperatorRunService.publishEvent(TicketOperatorEvent.builder()
                .ticketId(lane.getTicketId())
                .ticketKey(lane.getTicketKey())
                .laneId(lane.getLaneId())
                .executionId(executionId)
                .agentId(lane.getAgent().getId())
                .scope(lane.getScope())
                .stepId(from.getId())
                .stepTitle(to.getTitle())
                .stepOrder(to.getOrder())
                .eventType("NEXT_STEP")
                .message("Next step: " + from.getId() + " -> " + to.getId())
                .timestamp(Instant.now())
                .build());
    }

    public void publishLaneCompleted(final ReadyToStartLane lane, final UUID executionId, final LaneStrategyStep step, final int totalSteps) {
        this.publishSimple(lane, executionId, step, totalSteps, "LANE_COMPLETED", "Lane completed");
        final TicketOperatorRun run = this.ticketOperatorRunService.markCompletedIfTerminal(lane.getTicketId());
        if ("COMPLETED".equals(run.getStatus().name())) {
            this.ticketOperatorRunService.publishEvent(this.ticketOperatorRunService.ticketEvent(
                    lane.getTicketId(),
                    lane.getTicketKey(),
                    "TICKET_COMPLETED",
                    "Ticket completed"
            ));
        }
    }

    public void publishStepResponseReceived(final ReadyToStartLane lane, final UUID executionId, final LaneStrategyStep step, final int totalSteps) {
        this.publishSimple(lane, executionId, step, totalSteps, "STEP_RESPONSE_RECEIVED", "Step response received");
    }

    public void publishStepValidationFailed(final ReadyToStartLane lane,
                                            final UUID executionId,
                                            final LaneStrategyStep step,
                                            final int totalSteps,
                                            final String message) {
        this.publishSimple(lane, executionId, step, totalSteps, "STEP_VALIDATION_FAILED", message);
    }

    public void publishCorrectionStarted(final ReadyToStartLane lane,
                                         final UUID executionId,
                                         final LaneStrategyStep step,
                                         final int totalSteps,
                                         final String message) {
        this.publishSimple(lane, executionId, step, totalSteps, "CORRECTION_STARTED", message);
    }

    public void publishLaneFailed(final ReadyToStartLane lane,
                                  final UUID executionId,
                                  final LaneStrategyStep step,
                                  final int totalSteps,
                                  final String message) {
        this.publishSimple(lane, executionId, step, totalSteps, "LANE_FAILED", message);
    }

    public void publishLaneInterrupted(final ReadyToStartLane lane, final UUID executionId, final String message) {
        final LaneExecution execution = this.getExecution(executionId);
        this.ticketOperatorRunService.publishEvent(TicketOperatorEvent.builder()
                .ticketId(lane.getTicketId())
                .ticketKey(lane.getTicketKey())
                .laneId(lane.getLaneId())
                .executionId(executionId)
                .agentId(lane.getAgent().getId())
                .scope(lane.getScope())
                .codexProcessPid(execution.getProcessPid())
                .codexSessionId(execution.getSessionId())
                .codexThreadId(execution.getThreadId())
                .activeTurnId(execution.getActiveTurnId())
                .eventType("LANE_INTERRUPTED")
                .message(message)
                .timestamp(Instant.now())
                .build());
    }

    @Override
    public void onEvent(final CodexProgressEvent event) {
        if (event == null || event.executionId() == null) {
            return;
        }
        this.progressLog.info(this.formatProgress(event));
        final LaneExecution updated = this.updateExecution(event.executionId(), execution -> {
            final LaneExecution.LaneExecutionBuilder builder = execution.toBuilder()
                    .lastProgressEvent(event.eventType().name())
                    .lastProgressAt(this.asLocalDateTime(event.occurredAt()))
                    .lastCodexEventType(event.eventType().name())
                    .updatedAt(LocalDateTime.now());
            if (event.sessionId() != null) {
                builder.sessionId(event.sessionId());
            }
            if (event.threadId() != null) {
                builder.threadId(event.threadId());
            }
            if (event.turnId() != null) {
                builder.activeTurnId(event.turnId());
            }
            if (event.stepId() != null) {
                builder.currentStepId(event.stepId());
            }
            if (event.stepOrder() != null) {
                builder.currentStepOrder(event.stepOrder());
            }
            if (event.stepTitle() != null) {
                builder.currentStepTitle(event.stepTitle());
            }
            if (event.processPid() != null) {
                builder.processPid(event.processPid());
            }
            if (event.command() != null && event.eventType() == CodexProgressEventType.PROCESS_STARTED) {
                builder.processCommand(event.command());
            }
            if (event.cwd() != null && event.eventType() == CodexProgressEventType.PROCESS_STARTED) {
                builder.processCwd(event.cwd());
            }
            if (event.codexVersion() != null) {
                builder.codexVersion(event.codexVersion());
            }
            if (event.eventType() == CodexProgressEventType.PROCESS_STARTED) {
                builder.processStartedAt(this.asLocalDateTime(event.occurredAt()));
            }
            if (event.eventType() == CodexProgressEventType.SESSION_STARTED) {
                builder.status(LaneExecutionStatus.SESSION_STARTED);
            }
            if (event.eventType() == CodexProgressEventType.TURN_STARTED) {
                builder.status(LaneExecutionStatus.TURN_RUNNING);
            }
            if (event.eventType() == CodexProgressEventType.TURN_INTERRUPTED) {
                builder.status(LaneExecutionStatus.INTERRUPTED).interruptedAt(LocalDateTime.now());
            }
            if (event.eventType() == CodexProgressEventType.PROCESS_STDERR) {
                builder.stderrTail(this.appendStderr(execution.getStderrTail(), event.text()));
            }
            return builder.build();
        });
        this.ticketOperatorRunService.publishEvent(this.asTicketEvent(updated, event));
    }

    private LaneExecution updateStatus(final UUID executionId, final LaneExecutionStatus status) {
        return this.updateExecution(executionId, execution -> execution.toBuilder()
                .status(status)
                .updatedAt(LocalDateTime.now())
                .build());
    }

    private LaneExecution updateExecution(final UUID executionId, final UnaryOperator<LaneExecution> updater) {
        final LaneExecution updated = updater.apply(this.getExecution(executionId));
        return this.laneExecutionRepository.saveExecution(updated);
    }

    private LocalDateTime asLocalDateTime(final Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private List<String> appendStderr(final List<String> currentTail, final String line) {
        final List<String> updated = new ArrayList<>(currentTail == null ? List.of() : currentTail);
        if (line != null && !line.isBlank()) {
            updated.add(line);
        }
        final int from = Math.max(0, updated.size() - STDERR_TAIL_LIMIT);
        return List.copyOf(updated.subList(from, updated.size()));
    }

    private String formatProgress(final CodexProgressEvent event) {
        final StringBuilder message = new StringBuilder("[forge-codex] ")
                .append(event.eventType().name())
                .append(" executionId=").append(event.executionId());
        if (event.laneId() != null) {
            message.append(" laneId=").append(event.laneId());
        }
        if (event.agentId() != null) {
            message.append(" agent=").append(event.agentId());
        }
        if (event.scope() != null) {
            message.append(" scope=").append(event.scope());
        }
        if (event.sessionId() != null) {
            message.append(" sessionId=").append(event.sessionId());
        }
        if (event.processPid() != null) {
            message.append(" pid=").append(event.processPid());
        }
        if (event.threadId() != null) {
            message.append(" threadId=").append(event.threadId());
        }
        if (event.turnId() != null) {
            message.append(" turnId=").append(event.turnId());
        }
        if (event.stepId() != null) {
            message.append(" step=").append(event.stepId());
        }
        if (event.stepOrder() != null) {
            message.append(" order=").append(event.stepOrder());
        }
        if (event.stepTitle() != null) {
            message.append(" title=").append('"').append(event.stepTitle()).append('"');
        }
        if (event.command() != null) {
            message.append(" command=").append('"').append(event.command()).append('"');
        }
        if (event.cwd() != null) {
            message.append(" cwd=").append('"').append(event.cwd()).append('"');
        }
        if (event.stream() != null) {
            message.append(" stream=").append(event.stream());
        }
        if (event.status() != null) {
            message.append(" status=").append(event.status());
        }
        if (event.fileCount() != null) {
            message.append(" files=").append(event.fileCount());
        }
        if (event.chars() != null) {
            message.append(" chars=").append(event.chars());
        }
        if (event.durationMs() != null) {
            message.append(" durationMs=").append(event.durationMs());
        }
        if (event.text() != null && !event.text().isBlank()) {
            message.append(" text=").append('"').append(event.text().replace('"', '\'')).append('"');
        }
        return message.toString();
    }

    private void publishSimple(final ReadyToStartLane lane,
                               final UUID executionId,
                               final LaneStrategyStep step,
                               final Integer totalSteps,
                               final String eventType,
                               final String message) {
        final LaneExecution execution = this.getExecution(executionId);
        this.ticketOperatorRunService.publishEvent(TicketOperatorEvent.builder()
                .ticketId(lane.getTicketId())
                .ticketKey(lane.getTicketKey())
                .laneId(lane.getLaneId())
                .executionId(executionId)
                .agentId(lane.getAgent().getId())
                .scope(lane.getScope())
                .stepId(step.getId())
                .stepTitle(step.getTitle())
                .stepOrder(step.getOrder())
                .totalSteps(totalSteps)
                .codexProcessPid(execution.getProcessPid())
                .codexSessionId(execution.getSessionId())
                .codexThreadId(execution.getThreadId())
                .activeTurnId(execution.getActiveTurnId())
                .eventType(eventType)
                .message(message)
                .timestamp(Instant.now())
                .build());
    }

    private TicketOperatorEvent asTicketEvent(final LaneExecution execution, final CodexProgressEvent event) {
        return TicketOperatorEvent.builder()
                .ticketId(execution.getTicketId())
                .laneId(execution.getLaneId())
                .executionId(execution.getId())
                .agentId(execution.getAgentId())
                .scope(execution.getScope())
                .stepId(event.stepId())
                .stepTitle(event.stepTitle())
                .stepOrder(event.stepOrder())
                .codexProcessPid(event.processPid() == null ? execution.getProcessPid() : event.processPid())
                .codexSessionId(event.sessionId() == null ? execution.getSessionId() : event.sessionId())
                .codexThreadId(event.threadId() == null ? execution.getThreadId() : event.threadId())
                .activeTurnId(event.turnId() == null ? execution.getActiveTurnId() : event.turnId())
                .eventType(this.mapEventType(event.eventType()))
                .message(this.mapMessage(event))
                .timestamp(event.occurredAt())
                .build();
    }

    private String mapEventType(final CodexProgressEventType eventType) {
        return switch (eventType) {
            case TURN_STARTED -> "TURN_STARTED";
            case TURN_PLAN_UPDATED -> "PLAN";
            case ITEM_STARTED -> "ITEM_STARTED";
            case COMMAND_STARTED -> "COMMAND_STARTED";
            case COMMAND_OUTPUT -> "COMMAND_OUTPUT";
            case COMMAND_COMPLETED -> "COMMAND_COMPLETED";
            case FILE_CHANGE -> "FILE_CHANGE";
            case AGENT_MESSAGE_DELTA -> "AGENT_MESSAGE_DELTA";
            case TURN_COMPLETED -> "TURN_COMPLETED";
            case PROCESS_STDERR -> "PROCESS_STDERR";
            case PROCESS_STARTED -> "PROCESS_STARTED";
            case SESSION_STARTED -> "SESSION_STARTED";
            case HEARTBEAT -> "HEARTBEAT";
            case TURN_INTERRUPT_SENT -> "TURN_INTERRUPT_SENT";
            case TURN_INTERRUPTED -> "TURN_INTERRUPTED";
            case PROCESS_TERMINATED -> "PROCESS_TERMINATED";
            case SERVER_REQUEST -> "SERVER_REQUEST";
        };
    }

    private String mapMessage(final CodexProgressEvent event) {
        if (event.text() != null && !event.text().isBlank()) {
            return event.text();
        }
        if (event.command() != null && !event.command().isBlank()) {
            return event.command();
        }
        if (event.status() != null && !event.status().isBlank()) {
            return event.status();
        }
        return event.eventType().name();
    }
}
