package com.sitionix.forgeagent.domain.exception;

public final class NotFoundException extends ForgeAgentException {

    public NotFoundException(final String code, final String message) {
        super(code, message);
    }
}
