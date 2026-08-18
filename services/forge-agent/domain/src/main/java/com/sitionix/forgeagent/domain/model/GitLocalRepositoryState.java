package com.sitionix.forgeagent.domain.model;

public record GitLocalRepositoryState(
        boolean valid,
        GitHeadState head,
        GitWorkingTreeState workingTree,
        GitConflictState conflictState,
        GitOperationState operationState,
        GitUpstreamState upstream,
        boolean pullAvailable
) {

    public static GitLocalRepositoryState invalid() {
        return new GitLocalRepositoryState(false, null, null, null, null, null, false);
    }

    public static GitLocalRepositoryState valid(final GitHeadState head,
                                                final GitWorkingTreeState workingTree,
                                                final GitConflictState conflictState,
                                                final GitOperationState operationState,
                                                final GitUpstreamState upstream) {
        return new GitLocalRepositoryState(
                true,
                head,
                workingTree,
                conflictState,
                operationState,
                upstream,
                isPullAvailable(head, workingTree, conflictState, operationState, upstream)
        );
    }

    public GitLocalRepositoryState withUpstreamRelation(final GitUpstreamRelation relation) {
        if (!this.valid || this.upstream == null) {
            return this;
        }
        return valid(this.head, this.workingTree, this.conflictState, this.operationState,
                new GitUpstreamState(this.upstream.ref(), relation));
    }

    public GitLocalRepositoryState withoutPullAvailable() {
        if (!this.valid) {
            return this;
        }
        return new GitLocalRepositoryState(
                true,
                this.head,
                this.workingTree,
                this.conflictState,
                this.operationState,
                this.upstream,
                false
        );
    }

    private static boolean isPullAvailable(final GitHeadState head,
                                           final GitWorkingTreeState workingTree,
                                           final GitConflictState conflictState,
                                           final GitOperationState operationState,
                                           final GitUpstreamState upstream) {
        return head != null
                && head.type() == GitHeadType.BRANCH
                && head.commit() != null
                && conflictState != GitConflictState.CONFLICTED
                && workingTree == GitWorkingTreeState.CLEAN
                && operationState != GitOperationState.IN_PROGRESS
                && upstream != null
                && upstream.relation() == GitUpstreamRelation.BEHIND;
    }
}
