package com.sitionix.forgeagent.infrastructure.codex;

/** Marks failures owned by Forge's MCP integration rather than the Codex provider turn itself. */
final class CodexMcpExecutionException extends CodexTransportException {
    CodexMcpExecutionException(final String message) {
        super(message);
    }

    CodexMcpExecutionException(final String message, final Throwable cause) {
        super(message, cause);
    }

    static boolean causedBy(final Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof CodexMcpExecutionException) return true;
            final Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return false;
    }
}
