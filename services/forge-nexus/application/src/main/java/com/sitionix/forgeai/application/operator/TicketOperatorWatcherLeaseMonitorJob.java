package com.sitionix.forgeai.application.operator;

import com.sitionix.forgeai.domain.usecase.ManageTicketOperatorRuns;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Log
@Component
@RequiredArgsConstructor
public class TicketOperatorWatcherLeaseMonitorJob {

    private final ManageTicketOperatorRuns manageTicketOperatorRuns;

    @Scheduled(fixedDelayString = "#{@ticketOperatorTerminalProperties.heartbeatInterval.toMillis()}")
    public void run() {
        this.manageTicketOperatorRuns.findActiveTicketRuns().stream()
                .filter(run -> run.isStopOnWindowClose())
                .filter(run -> run.getWatcherId() != null && !run.getWatcherId().isBlank())
                .filter(run -> run.getLastHeartbeatAt() != null)
                .filter(run -> run.getLastHeartbeatAt().plus(this.manageTicketOperatorRuns.watcherHeartbeatTimeout()).isBefore(LocalDateTime.now()))
                .forEach(run -> {
                    log.info("Expiring ticket operator watcher ticketId=" + run.getTicketId() + ", watcherId=" + run.getWatcherId());
                    this.manageTicketOperatorRuns.interruptTicket(run.getTicketId(), "OPERATOR_TICKET_TERMINAL_HEARTBEAT_EXPIRED");
                });
    }
}
