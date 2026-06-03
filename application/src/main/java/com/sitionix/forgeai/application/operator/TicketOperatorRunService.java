package com.sitionix.forgeai.application.operator;

import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorEvent;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorRun;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorRunStatus;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.repository.LaneExecutionRepository;
import com.sitionix.forgeai.domain.repository.TicketOperatorRunRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketOperatorRunService {

    private final TicketOperatorRunRepository ticketOperatorRunRepository;
    private final TicketRepository ticketRepository;
    private final LaneExecutionRepository laneExecutionRepository;
    private final TicketOperatorEventService ticketOperatorEventService;

    public TicketOperatorRun initializeRun(final Ticket ticket) {
        final TicketOperatorRun run = this.ticketOperatorRunRepository.findByTicketId(ticket.getId())
                .orElseGet(() -> this.ticketOperatorRunRepository.save(TicketOperatorRun.builder()
                        .ticketId(ticket.getId())
                        .ticketKey(ticket.getTicketKey())
                        .status(TicketOperatorRunStatus.OPENING)
                        .stopOnWindowClose(true)
                        .activeExecutionIds(List.of())
                        .activeLaneIds(List.of())
                        .build()));
        this.publishEvent(this.ticketEvent(ticket.getId(), ticket.getTicketKey(), "TICKET_STARTED", "Ticket created"));
        return run;
    }

    public TicketOperatorRun registerWatcher(final UUID ticketId, final String watcherId, final boolean stopOnWindowClose) {
        return this.update(ticketId, run -> run.toBuilder()
                .watcherId(watcherId)
                .terminalOpenedAt(run.getTerminalOpenedAt() == null ? LocalDateTime.now() : run.getTerminalOpenedAt())
                .lastHeartbeatAt(LocalDateTime.now())
                .stopOnWindowClose(stopOnWindowClose)
                .status(this.laneExecutionRepository.findActiveExecutionsByTicketId(ticketId).isEmpty()
                        ? TicketOperatorRunStatus.WATCHING
                        : TicketOperatorRunStatus.RUNNING)
                .build());
    }

    public TicketOperatorRun claimWatcherOpen(final UUID ticketId, final String watcherId, final boolean stopOnWindowClose) {
        return this.update(ticketId, run -> {
            if (this.hasActiveWatcher(run)) {
                return run;
            }
            return run.toBuilder()
                    .watcherId(watcherId)
                    .terminalOpenedAt(LocalDateTime.now())
                    .stopOnWindowClose(stopOnWindowClose)
                    .status(TicketOperatorRunStatus.OPENING)
                    .lastProgressEvent("TICKET_TERMINAL_OPEN_REQUESTED")
                    .lastProgressAt(LocalDateTime.now())
                    .build();
        });
    }

    public TicketOperatorRun releaseWatcherClaim(final UUID ticketId, final String watcherId) {
        return this.update(ticketId, run -> {
            if (run.getWatcherId() == null || !run.getWatcherId().equals(watcherId)) {
                return run;
            }
            return run.toBuilder()
                    .watcherId(null)
                    .status(this.laneExecutionRepository.findActiveExecutionsByTicketId(ticketId).isEmpty()
                            ? TicketOperatorRunStatus.WATCHING
                            : TicketOperatorRunStatus.RUNNING)
                    .lastProgressEvent("TICKET_TERMINAL_OPEN_FAILED")
                    .lastProgressAt(LocalDateTime.now())
                    .build();
        });
    }

    public TicketOperatorRun heartbeat(final UUID ticketId, final String watcherId) {
        return this.update(ticketId, run -> run.toBuilder()
                .watcherId(watcherId)
                .lastHeartbeatAt(LocalDateTime.now())
                .status(run.getStatus() == TicketOperatorRunStatus.OPENING ? TicketOperatorRunStatus.WATCHING : run.getStatus())
                .build());
    }

    public TicketOperatorRun markExecutionStarted(final LaneExecution execution) {
        return this.update(execution.getTicketId(), run -> {
            final List<UUID> executionIds = new ArrayList<>(run.getActiveExecutionIds() == null ? List.of() : run.getActiveExecutionIds());
            if (!executionIds.contains(execution.getId())) {
                executionIds.add(execution.getId());
            }
            final List<UUID> laneIds = new ArrayList<>(run.getActiveLaneIds() == null ? List.of() : run.getActiveLaneIds());
            if (!laneIds.contains(execution.getLaneId())) {
                laneIds.add(execution.getLaneId());
            }
            return run.toBuilder()
                    .status(run.getWatcherId() == null ? TicketOperatorRunStatus.OPENING : TicketOperatorRunStatus.RUNNING)
                    .activeExecutionIds(List.copyOf(executionIds))
                    .activeLaneIds(List.copyOf(laneIds))
                    .lastProgressEvent("LANE_STARTED")
                    .lastProgressAt(LocalDateTime.now())
                    .build();
        });
    }

    public TicketOperatorRun markExecutionFinished(final LaneExecution execution) {
        return this.updateIfPresent(execution.getTicketId(), run -> {
            final List<UUID> executionIds = new ArrayList<>(run.getActiveExecutionIds() == null ? List.of() : run.getActiveExecutionIds());
            executionIds.remove(execution.getId());
            final List<UUID> laneIds = new ArrayList<>(run.getActiveLaneIds() == null ? List.of() : run.getActiveLaneIds());
            laneIds.remove(execution.getLaneId());
            final TicketOperatorRunStatus status = switch (run.getStatus()) {
                case CANCEL_REQUESTED, INTERRUPTING, CANCELLED, COMPLETED, FAILED, DISCONNECTED -> run.getStatus();
                default -> executionIds.isEmpty() ? TicketOperatorRunStatus.WATCHING : TicketOperatorRunStatus.RUNNING;
            };
            return run.toBuilder()
                    .status(status)
                    .activeExecutionIds(List.copyOf(executionIds))
                    .activeLaneIds(List.copyOf(laneIds))
                    .lastProgressAt(LocalDateTime.now())
                    .build();
        });
    }

    public TicketOperatorRun recordProgress(final TicketOperatorEvent event) {
        return this.updateIfPresent(event.getTicketId(), run -> run.toBuilder()
                .lastProgressEvent(event.getEventType())
                .lastProgressAt(LocalDateTime.now())
                .build());
    }

    public TicketOperatorRun markCancelRequested(final UUID ticketId, final String reason) {
        return this.update(ticketId, run -> run.toBuilder()
                .status(TicketOperatorRunStatus.CANCEL_REQUESTED)
                .cancelRequestedAt(LocalDateTime.now())
                .interruptReason(reason)
                .lastProgressEvent("TICKET_INTERRUPT_REQUESTED")
                .lastProgressAt(LocalDateTime.now())
                .build());
    }

    public TicketOperatorRun markInterrupting(final UUID ticketId, final String reason) {
        return this.update(ticketId, run -> run.toBuilder()
                .status(TicketOperatorRunStatus.INTERRUPTING)
                .interruptReason(reason)
                .lastProgressEvent("TICKET_INTERRUPTING")
                .lastProgressAt(LocalDateTime.now())
                .build());
    }

    public TicketOperatorRun markCancelled(final UUID ticketId, final String reason) {
        return this.update(ticketId, run -> run.toBuilder()
                .status(TicketOperatorRunStatus.CANCELLED)
                .interruptReason(reason)
                .cancelledAt(LocalDateTime.now())
                .activeExecutionIds(List.of())
                .activeLaneIds(List.of())
                .lastProgressEvent("TICKET_CANCELLED")
                .lastProgressAt(LocalDateTime.now())
                .build());
    }

    public TicketOperatorRun markCompletedIfTerminal(final UUID ticketId) {
        final Ticket ticket = this.ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalStateException("Ticket not found: " + ticketId));
        final boolean terminal = ticket.getLanes().stream()
                .allMatch(lane -> lane.getStatus() == LaneStatus.COMPLETED || lane.getStatus() == LaneStatus.NOT_NEEDED);
        if (!terminal) {
            return this.get(ticketId);
        }
        return this.update(ticketId, run -> run.toBuilder()
                .status(run.getStatus() == TicketOperatorRunStatus.CANCELLED
                        || run.getStatus() == TicketOperatorRunStatus.CANCEL_REQUESTED
                        || run.getStatus() == TicketOperatorRunStatus.INTERRUPTING
                        ? run.getStatus()
                        : TicketOperatorRunStatus.COMPLETED)
                .activeExecutionIds(List.of())
                .activeLaneIds(List.of())
                .lastProgressEvent(run.getStatus() == TicketOperatorRunStatus.CANCELLED ? run.getLastProgressEvent() : "TICKET_COMPLETED")
                .lastProgressAt(LocalDateTime.now())
                .build());
    }

    public boolean isExecutionBlocked(final UUID ticketId) {
        return this.ticketOperatorRunRepository.findByTicketId(ticketId)
                .map(run -> run.getStatus() == TicketOperatorRunStatus.CANCEL_REQUESTED
                        || run.getStatus() == TicketOperatorRunStatus.INTERRUPTING
                        || run.getStatus() == TicketOperatorRunStatus.CANCELLED)
                .orElse(false);
    }

    public boolean hasActiveWatcher(final UUID ticketId) {
        return this.ticketOperatorRunRepository.findByTicketId(ticketId)
                .map(this::hasActiveWatcher)
                .orElse(false);
    }

    public TicketOperatorRun get(final UUID ticketId) {
        return this.ticketOperatorRunRepository.findByTicketId(ticketId)
                .orElseThrow(() -> new IllegalStateException("Unknown ticket operator run for ticketId=" + ticketId));
    }

    public List<TicketOperatorRun> findActiveRuns() {
        return this.ticketOperatorRunRepository.findActiveRuns();
    }

    public List<TicketOperatorRun> findExpiredWatchers(final LocalDateTime cutoff) {
        return this.ticketOperatorRunRepository.findActiveRuns().stream()
                .filter(run -> run.isStopOnWindowClose())
                .filter(run -> run.getWatcherId() != null && !run.getWatcherId().isBlank())
                .filter(run -> run.getLastHeartbeatAt() != null && run.getLastHeartbeatAt().isBefore(cutoff))
                .filter(run -> run.getStatus() == TicketOperatorRunStatus.WATCHING
                        || run.getStatus() == TicketOperatorRunStatus.RUNNING
                        || run.getStatus() == TicketOperatorRunStatus.OPENING)
                .toList();
    }

    public void publishEvent(final TicketOperatorEvent event) {
        this.ticketOperatorEventService.publish(event);
        if (event != null && event.getTicketId() != null) {
            this.recordProgress(event);
        }
    }

    public List<TicketOperatorEvent> recentEvents(final UUID ticketId) {
        return this.ticketOperatorEventService.recentEvents(ticketId);
    }

    public TicketOperatorEvent ticketEvent(final UUID ticketId, final String ticketKey, final String eventType, final String message) {
        return TicketOperatorEvent.builder()
                .ticketId(ticketId)
                .ticketKey(ticketKey)
                .eventType(eventType)
                .message(message)
                .timestamp(Instant.now())
                .build();
    }

    private TicketOperatorRun update(final UUID ticketId, final UnaryOperator<TicketOperatorRun> updater) {
        final TicketOperatorRun existing = this.ticketOperatorRunRepository.findByTicketId(ticketId)
                .orElseGet(() -> {
                    final Ticket ticket = this.ticketRepository.findById(ticketId)
                            .orElseThrow(() -> new IllegalStateException("Ticket not found: " + ticketId));
                    return this.initializeRun(ticket);
                });
        return this.ticketOperatorRunRepository.save(updater.apply(existing));
    }

    private TicketOperatorRun updateIfPresent(final UUID ticketId, final UnaryOperator<TicketOperatorRun> updater) {
        return this.ticketOperatorRunRepository.findByTicketId(ticketId)
                .map(updater)
                .map(this.ticketOperatorRunRepository::save)
                .orElse(null);
    }

    private boolean hasActiveWatcher(final TicketOperatorRun run) {
        return run.getWatcherId() != null
                && !run.getWatcherId().isBlank()
                && switch (run.getStatus()) {
                    case OPENING, WATCHING, RUNNING -> true;
                    default -> false;
                };
    }
}
