package com.sitionix.forgeai.application.infrastructure.knowledge;

public class KnowledgeGatewayException extends RuntimeException {

    private final KnowledgeGatewayErrorCode code;
    private final String responseCode;

    public KnowledgeGatewayException(final KnowledgeGatewayErrorCode code, final String message) {
        super(message);
        this.code = code;
        this.responseCode = code.name();
    }

    public KnowledgeGatewayException(final KnowledgeGatewayErrorCode code, final String message, final Throwable cause) {
        super(message, cause);
        this.code = code;
        this.responseCode = code.name();
    }

    public KnowledgeGatewayException(final KnowledgeGatewayErrorCode code, final String responseCode, final String message) {
        super(message);
        this.code = code;
        this.responseCode = responseCode == null || responseCode.isBlank() ? code.name() : responseCode;
    }

    public KnowledgeGatewayErrorCode getCode() {
        return this.code;
    }

    public String getResponseCode() {
        return this.responseCode;
    }
}
