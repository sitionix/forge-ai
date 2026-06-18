package com.sitionix.forgeai.domain.exception;

import java.util.UUID;

public class TicketNotFoundException extends RuntimeException {

    public TicketNotFoundException(final UUID ticketId) {
        super("Ticket not found: " + ticketId);
    }
}
