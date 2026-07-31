package com.sitionix.forgeai.domain.exception;

public final class KnowledgeActiveProfileClientException extends RuntimeException {

    private final KnowledgeActiveProfileFailureReason reason;
    private final String code;
    private final String correlationId;

    public KnowledgeActiveProfileClientException(final KnowledgeActiveProfileFailureReason reason,
                                                 final String code,
                                                 final String message,
                                                 final String correlationId) {
        super(message);
        this.reason = reason;
        this.code = code;
        this.correlationId = correlationId;
    }

    public KnowledgeActiveProfileFailureReason reason() {
        return this.reason;
    }

    public String code() {
        return this.code;
    }

    public String correlationId() {
        return this.correlationId;
    }
}
