package com.sitionix.forgeagent.infrastructure.codex;

class CodexTransportException extends RuntimeException {

    CodexTransportException(final String message) {
        super(message);
    }

    CodexTransportException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
