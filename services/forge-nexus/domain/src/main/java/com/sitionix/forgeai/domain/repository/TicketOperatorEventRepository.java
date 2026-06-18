package com.sitionix.forgeai.domain.repository;

import com.sitionix.forgeai.domain.model.operator.TicketOperatorEvent;
import java.util.List;
import java.util.UUID;

public interface TicketOperatorEventRepository {

    TicketOperatorEvent save(TicketOperatorEvent event);

    List<TicketOperatorEvent> findRecentByTicketId(UUID ticketId, int limit);

    void deleteByTicketId(UUID ticketId);
}
