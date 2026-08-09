package com.sitionix.forgeagent.domain.exception;

public final class ValidationException extends ForgeAgentException {

    public ValidationException(final String code, final String message) {
        super(code, message);
    }
}
