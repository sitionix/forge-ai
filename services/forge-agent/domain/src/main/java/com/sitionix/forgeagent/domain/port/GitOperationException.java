package com.sitionix.forgeagent.domain.port;

public class GitOperationException extends RuntimeException {

    public GitOperationException(final String message) {
        super(message);
    }

    public GitOperationException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
