package com.sitionix.forgeagent.domain.port;

public final class GitRemoteRejectedException extends GitOperationException {

    public GitRemoteRejectedException(final String message) {
        super(message);
    }

    public GitRemoteRejectedException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
