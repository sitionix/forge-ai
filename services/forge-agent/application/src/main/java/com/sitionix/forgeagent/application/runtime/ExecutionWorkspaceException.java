package com.sitionix.forgeagent.application.runtime;

public class ExecutionWorkspaceException extends RuntimeException {

    public ExecutionWorkspaceException(final String message) {
        super(message);
    }

    public ExecutionWorkspaceException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
