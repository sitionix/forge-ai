package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.operator.TicketOperatorEvent;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorRun;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

public interface ManageTicketOperatorRuns {

    TicketOperatorRun getTicketRun(UUID ticketId);

    List<TicketOperatorRun> findActiveTicketRuns();

    List<TicketOperatorEvent> recentEvents(UUID ticketId, String verbosity);

    void publishEvent(TicketOperatorEvent event);

    TicketOperatorRun registerWatcher(UUID ticketId, String watcherId, boolean stopOnWindowClose);

    TicketOperatorEventStream stream(UUID ticketId, String watcherId, String verbosity, boolean stopOnWindowClose);

    TicketOperatorRun heartbeat(UUID ticketId, String watcherId);

    TicketOperatorRun interruptTicket(UUID ticketId, String reason);

    boolean isExecutionBlocked(UUID ticketId);

    Duration watcherHeartbeatTimeout();
}
