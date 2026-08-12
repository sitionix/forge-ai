package com.sitionix.forgeagent.application.runtime;

public final class AgentExecutionException extends RuntimeException {

    private final String code;
    private final String safeMessage;

    public AgentExecutionException(final String code, final String safeMessage) {
        super(safeMessage);
        this.code = code;
        this.safeMessage = safeMessage;
    }

    public AgentExecutionException(final String code, final String safeMessage, final Throwable cause) {
        super(safeMessage, cause);
        this.code = code;
        this.safeMessage = safeMessage;
    }

    public String code() {
        return this.code;
    }

    public String safeMessage() {
        return this.safeMessage;
    }
}
