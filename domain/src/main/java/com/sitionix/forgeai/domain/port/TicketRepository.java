package com.sitionix.forgeai.domain.port;

import com.sitionix.forgeai.domain.model.ticket.Ticket;

/**
 * Persists Forge AI tickets.
 */
public interface TicketRepository {

    Ticket save(Ticket ticket);
}
