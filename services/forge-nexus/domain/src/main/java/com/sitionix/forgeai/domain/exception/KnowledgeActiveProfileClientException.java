package com.sitionix.forgeai.domain.exception;

public final class KnowledgeActiveProfileClientException extends RuntimeException {

    private final int status;
    private final String code;
    private final String correlationId;

    public KnowledgeActiveProfileClientException(final int status,
                                                 final String code,
                                                 final String message,
                                                 final String correlationId) {
        super(message);
        this.status = status;
        this.code = code;
        this.correlationId = correlationId;
    }

    public int status() {
        return this.status;
    }

    public String code() {
        return this.code;
    }

    public String correlationId() {
        return this.correlationId;
    }
}
