package com.sitionix.forgeai.domain.exception;

public final class KnowledgeClientException extends RuntimeException {

    private final int statusCode;
    private final String code;
    private final String correlationId;

    public KnowledgeClientException(final int statusCode,
                                    final String code,
                                    final String message,
                                    final String correlationId,
                                    final Throwable cause) {
        super(message, cause);
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("statusCode must be a valid HTTP status");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        this.statusCode = statusCode;
        this.code = code;
        this.correlationId = correlationId;
    }

    public int statusCode() {
        return this.statusCode;
    }

    public String code() {
        return this.code;
    }

    public String correlationId() {
        return this.correlationId;
    }
}
