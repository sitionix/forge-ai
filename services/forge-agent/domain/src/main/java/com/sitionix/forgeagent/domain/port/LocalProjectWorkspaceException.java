package com.sitionix.forgeagent.domain.port;

public final class LocalProjectWorkspaceException extends RuntimeException {

    public LocalProjectWorkspaceException(final String message) {
        super(message);
    }

    public LocalProjectWorkspaceException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
