package com.sitionix.forgeai.application.operator;

import com.sitionix.forgeai.domain.model.ticket.Ticket;
import java.util.UUID;
import java.util.logging.Logger;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketOperatorTerminalAutoOpenService {

    private static final Logger log = Logger.getLogger(TicketOperatorTerminalAutoOpenService.class.getName());

    private final TicketOperatorRunService ticketOperatorRunService;
    private final TicketOperatorTerminalProperties properties;
    private final TicketOperatorTerminalLauncher ticketOperatorTerminalLauncher;

    @Value("${forge.ai.launcher.default-base-url:http://127.0.0.1:9099/fgaisox}")
    private String defaultBaseUrl;

    public void openIfConfigured(final Ticket ticket) {
        if (!this.properties.isEnabled() || !this.properties.isAutoOpenOnTicketStart()) {
            return;
        }
        if (this.ticketOperatorRunService.hasActiveWatcher(ticket.getId())) {
            return;
        }
        final String watcherId = UUID.randomUUID().toString();
        this.ticketOperatorRunService.claimWatcherOpen(ticket.getId(), watcherId, this.properties.isStopOnWindowClose());
        final boolean opened = this.ticketOperatorTerminalLauncher.openTicketTerminal(
                ticket.getId(),
                ticket.getTicketKey(),
                this.defaultBaseUrl,
                watcherId,
                this.properties.getDefaultVerbosity()
        );
        if (opened) {
            log.info("Ticket operator terminal opened ticketId=" + ticket.getId() + " watcherId=" + watcherId);
            this.ticketOperatorRunService.publishEvent(this.ticketOperatorRunService.ticketEvent(
                    ticket.getId(),
                    ticket.getTicketKey(),
                    "TICKET_TERMINAL_OPENED",
                    "Ticket terminal opened: watcherId=" + watcherId
            ));
            return;
        }
        this.ticketOperatorRunService.releaseWatcherClaim(ticket.getId(), watcherId);
        log.warning("Ticket operator terminal auto-open failed for ticketId=" + ticket.getId());
    }
}
