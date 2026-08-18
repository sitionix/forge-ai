package com.sitionix.forgeagent.domain.port;

public final class GitExecutionException extends GitOperationException {

    public GitExecutionException(final String message) {
        super(message);
    }

    public GitExecutionException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
