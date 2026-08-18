package com.sitionix.forgeagent.domain.model;

public record GitLocalRepositoryState(
        boolean valid,
        GitHeadState head,
        GitWorkingTreeState workingTree
) {

    public static GitLocalRepositoryState invalid() {
        return new GitLocalRepositoryState(false, null, null);
    }
}
