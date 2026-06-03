package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.application.laneexecution.LaneExecutionProgressService;
import com.sitionix.forgeai.application.operator.TicketOperatorEventService;
import com.sitionix.forgeai.application.operator.TicketOperatorRunService;
import com.sitionix.forgeai.application.operator.TicketOperatorTerminalProperties;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorEvent;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorRun;
import com.sitionix.forgeai.domain.repository.CodexSessionRepository;
import com.sitionix.forgeai.domain.usecase.ManageTicketOperatorRuns;
import com.sitionix.forgeai.domain.usecase.TicketOperatorEventStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ManageTicketOperatorRunsUseCase implements ManageTicketOperatorRuns {

    private final TicketOperatorRunService ticketOperatorRunService;
    private final LaneExecutionProgressService laneExecutionProgressService;
    private final CodexSessionRepository codexSessionRepository;
    private final TicketOperatorTerminalProperties properties;
    private final TicketOperatorEventService eventService;

    @Override
    public TicketOperatorRun getTicketRun(final UUID ticketId) {
        return this.ticketOperatorRunService.get(ticketId);
    }

    @Override
    public List<TicketOperatorRun> findActiveTicketRuns() {
        return this.ticketOperatorRunService.findActiveRuns();
    }

    @Override
    public List<TicketOperatorEvent> recentEvents(final UUID ticketId, final String verbosity) {
        return this.eventService.filterByVerbosity(this.ticketOperatorRunService.recentEvents(ticketId), verbosity);
    }

    @Override
    public TicketOperatorRun registerWatcher(final UUID ticketId, final String watcherId, final boolean stopOnWindowClose) {
        return this.ticketOperatorRunService.registerWatcher(ticketId, watcherId, stopOnWindowClose);
    }

    @Override
    public TicketOperatorEventStream stream(final UUID ticketId,
                                            final String watcherId,
                                            final String verbosity,
                                            final boolean stopOnWindowClose) {
        final TicketOperatorRun existingRun = this.ticketOperatorRunService.get(ticketId);
        final boolean sameWatcherReconnect = watcherId.equals(existingRun.getWatcherId());
        this.registerWatcher(ticketId, watcherId, stopOnWindowClose);
        final TicketOperatorEventService.Subscription subscription = this.eventService.subscribe(ticketId);
        final List<TicketOperatorEvent> replay = this.recentEvents(ticketId, verbosity);
        if (!sameWatcherReconnect) {
            final TicketOperatorRun run = this.ticketOperatorRunService.get(ticketId);
            this.ticketOperatorRunService.publishEvent(this.ticketOperatorRunService.ticketEvent(
                    ticketId,
                    run.getTicketKey(),
                    "WATCHER_CONNECTED",
                    "Ticket watcher connected: watcherId=" + watcherId
            ));
        }
        return new TicketOperatorEventStream() {
            @Override
            public List<TicketOperatorEvent> replay() {
                return replay;
            }

            @Override
            public TicketOperatorEvent take() throws InterruptedException {
                while (true) {
                    final TicketOperatorEvent event = subscription.take();
                    if (eventService.includeByVerbosity(event, verbosity)) {
                        return event;
                    }
                }
            }

            @Override
            public void close() {
                subscription.close();
            }
        };
    }

    @Override
    public TicketOperatorRun heartbeat(final UUID ticketId, final String watcherId) {
        return this.ticketOperatorRunService.heartbeat(ticketId, watcherId);
    }

    @Override
    public TicketOperatorRun interruptTicket(final UUID ticketId, final String reason) {
        TicketOperatorRun run = this.ticketOperatorRunService.markCancelRequested(ticketId, reason);
        this.ticketOperatorRunService.publishEvent(this.ticketOperatorRunService.ticketEvent(
                ticketId,
                run.getTicketKey(),
                "TICKET_INTERRUPT_REQUESTED",
                reason
        ));
        run = this.ticketOperatorRunService.markInterrupting(ticketId, reason);
        for (final LaneExecution execution : this.laneExecutionProgressService.findActiveExecutionsByTicket(ticketId)) {
            this.laneExecutionProgressService.markCancelRequested(execution.getId());
            if (execution.getSessionId() != null && execution.getActiveTurnId() != null) {
                this.codexSessionRepository.interruptTurn(execution.getSessionId(), execution.getActiveTurnId(), Duration.ofSeconds(10));
            }
            if (execution.getSessionId() != null) {
                this.codexSessionRepository.closeSession(execution.getSessionId());
            }
            this.laneExecutionProgressService.markInterrupted(execution.getId(), "Ticket operator interrupt: " + reason);
        }
        run = this.ticketOperatorRunService.markCancelled(ticketId, reason);
        this.ticketOperatorRunService.publishEvent(this.ticketOperatorRunService.ticketEvent(
                ticketId,
                run.getTicketKey(),
                "TICKET_CANCELLED",
                reason
        ));
        return run;
    }

    @Override
    public boolean isExecutionBlocked(final UUID ticketId) {
        return this.ticketOperatorRunService.isExecutionBlocked(ticketId);
    }

    @Override
    public Duration watcherHeartbeatTimeout() {
        return this.properties.getHeartbeatTimeout();
    }
}
