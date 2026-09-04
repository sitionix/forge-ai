package com.sitionix.forgeagent.infrastructure.codex;

final class CodexExecutionException extends CodexTransportException {
    private final CodexExecutionFailurePhase phase;

    CodexExecutionException(final CodexExecutionFailurePhase phase, final String message) {
        super(message);
        this.phase = phase;
    }

    CodexExecutionException(final CodexExecutionFailurePhase phase, final String message, final Throwable cause) {
        super(message, cause);
        this.phase = phase;
    }

    CodexExecutionFailurePhase phase() {
        return this.phase;
    }
}
