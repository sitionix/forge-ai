package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.GitLocalRepositoryState;

public final class GitUnsafeRepositoryStateException extends GitOperationException {

    private final GitLocalRepositoryState state;

    public GitUnsafeRepositoryStateException(final String message, final GitLocalRepositoryState state) {
        super(message);
        this.state = state;
    }

    public GitLocalRepositoryState state() {
        return this.state;
    }
}
