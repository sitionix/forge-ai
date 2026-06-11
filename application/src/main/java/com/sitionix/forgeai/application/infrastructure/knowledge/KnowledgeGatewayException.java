package com.sitionix.forgeai.application.infrastructure.knowledge;

public class KnowledgeGatewayException extends RuntimeException {

    private final KnowledgeGatewayErrorCode code;

    public KnowledgeGatewayException(final KnowledgeGatewayErrorCode code, final String message) {
        super(message);
        this.code = code;
    }

    public KnowledgeGatewayException(final KnowledgeGatewayErrorCode code, final String message, final Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public KnowledgeGatewayErrorCode getCode() {
        return this.code;
    }
}
