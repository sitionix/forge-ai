package com.sitionix.forgeagent.domain.exception;

public abstract class ForgeAgentException extends RuntimeException {

    private final String code;

    protected ForgeAgentException(final String code, final String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return this.code;
    }
}
