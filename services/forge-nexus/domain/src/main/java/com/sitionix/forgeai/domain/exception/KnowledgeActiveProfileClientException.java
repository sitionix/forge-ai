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
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
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
