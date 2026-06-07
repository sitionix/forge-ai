package com.sitionix.forgeai.application.infrastructure.jarvis;

public class JarvisGatewayException extends RuntimeException {

    private final JarvisGatewayErrorCode code;

    public JarvisGatewayException(final JarvisGatewayErrorCode code, final String message) {
        super(message);
        this.code = code;
    }

    public JarvisGatewayException(final JarvisGatewayErrorCode code, final String message, final Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public JarvisGatewayErrorCode getCode() {
        return this.code;
    }
}
