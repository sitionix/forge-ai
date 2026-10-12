package com.sitionix.forgeagent.infrastructure.codex;

class CodexTransportException extends com.sitionix.forgeagent.domain.exception.ForgeAgentException {
    private final String upstreamCode;
    private final Boolean willRetry;
    private final String origin;

    CodexTransportException(final String message) {
        this(message, null);
    }

    CodexTransportException(final String message, final Throwable cause) {
        this(cause instanceof com.sitionix.forgeagent.domain.exception.ForgeAgentException typed
                        ? typed.code() : "AGENT_EXECUTOR_FAILED", message, cause,
                cause instanceof CodexTransportException typed ? typed.upstreamCode() : null,
                cause instanceof CodexTransportException typed ? typed.willRetry() : null,
                cause instanceof CodexTransportException typed ? typed.origin()
                        : cause instanceof CodexRemoteException ? "PROVIDER"
                        : cause instanceof java.io.IOException || cause instanceof java.util.concurrent.TimeoutException
                            ? "TRANSPORT" : "UNKNOWN");
    }

    private CodexTransportException(String code, String message, Throwable cause, String upstreamCode, Boolean willRetry, String origin) {
        super(code, com.sitionix.forgeagent.domain.model.ExecutionDiagnosticText.summary(message));
        if (cause != null) initCause(cause);
        this.upstreamCode = upstreamCode;
        this.willRetry = willRetry;
        this.origin = origin;
    }

    static CodexTransportException providerFailure(final com.fasterxml.jackson.databind.JsonNode error,
                                                   final Boolean willRetry) {
        final String code = upstreamCode(error);
        final String message = error.path("message").isTextual() ? error.path("message").asText() : null;
        return new CodexTransportException(code == null ? "AGENT_EXECUTOR_FAILED" : code,
                message == null || message.isBlank() ? "Codex execution failed." : message,
                null, code, willRetry, "PROVIDER");
    }

    static String upstreamCode(final com.fasterxml.jackson.databind.JsonNode error) {
        final var info = error.path("codexErrorInfo");
        final String candidate = info.isTextual() ? info.asText()
                : info.isObject() && info.size() == 1 ? info.fieldNames().next()
                : error.path("code").isTextual() || error.path("code").isIntegralNumber()
                    ? error.path("code").asText() : null;
        return com.sitionix.forgeagent.domain.model.ExecutionDiagnosticText.code(candidate);
    }

    String upstreamCode() { return this.upstreamCode; }
    Boolean willRetry() { return this.willRetry; }
    String origin() { return this.origin; }
}
