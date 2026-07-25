package com.sitionix.forgeai.application.usecase;

public class TicketExecutionCancelledException extends RuntimeException {

    public TicketExecutionCancelledException(final String message) {
        super(message);
    }
}
