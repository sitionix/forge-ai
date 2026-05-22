package com.sitionix.forgeai.api;

public class TicketNotFoundException extends RuntimeException {

    public TicketNotFoundException(final String message) {
        super(message);
    }
}
