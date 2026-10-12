package com.sitionix.forgeagent.domain.model;

public record NodeRunFailure(
        String code,
        String message
) {
    public NodeRunFailure {
        message = ExecutionDiagnosticText.summary(message);
    }

    public static NodeRunFailure from(final RuntimeException error, final String fallbackMessage) {
        final String code = error instanceof com.sitionix.forgeagent.domain.exception.ForgeAgentException typed
                ? typed.code() : "AGENT_EXECUTOR_FAILED";
        final String message = error.getMessage();
        return new NodeRunFailure(code, message == null || message.isBlank() ? fallbackMessage : message);
    }
}
