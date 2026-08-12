package com.sitionix.forgeagent.application.runtime;

import lombok.Getter;

public final class AgentExecutionException extends RuntimeException {

    @Getter
    private final String code;

    public AgentExecutionException(final String code, final String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    public AgentExecutionException(final String code, final String safeMessage, final Throwable cause) {
        super(safeMessage, cause);
        this.code = code;
    }
}
