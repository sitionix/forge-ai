package com.sitionix.forgeai.domain.repository;

import com.sitionix.forgeai.domain.model.operator.TicketOperatorRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketOperatorRunRepository {

    TicketOperatorRun save(TicketOperatorRun run);

    Optional<TicketOperatorRun> findByTicketId(UUID ticketId);

    List<TicketOperatorRun> findActiveRuns();
}
