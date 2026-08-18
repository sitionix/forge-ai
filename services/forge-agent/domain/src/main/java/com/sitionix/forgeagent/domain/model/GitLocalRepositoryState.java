package com.sitionix.forgeagent.domain.model;

public record GitLocalRepositoryState(
        boolean valid,
        GitHeadState head,
        GitWorkingTreeState workingTree,
        GitConflictState conflictState,
        GitOperationState operationState,
        GitUpstreamState upstream,
        boolean pullAllowed,
        String pullBlockedReason
) {

    public static GitLocalRepositoryState invalid() {
        return new GitLocalRepositoryState(false, null, null, null, null, null, false, "INVALID_CHECKOUT");
    }

    public static GitLocalRepositoryState valid(final GitHeadState head,
                                                final GitWorkingTreeState workingTree,
                                                final GitConflictState conflictState,
                                                final GitOperationState operationState,
                                                final GitUpstreamState upstream) {
        final String blockedReason = resolvePullBlockedReason(head, workingTree, conflictState, operationState, upstream);
        return new GitLocalRepositoryState(
                true,
                head,
                workingTree,
                conflictState,
                operationState,
                upstream,
                blockedReason == null,
                blockedReason
        );
    }

    private static String resolvePullBlockedReason(final GitHeadState head,
                                                   final GitWorkingTreeState workingTree,
                                                   final GitConflictState conflictState,
                                                   final GitOperationState operationState,
                                                   final GitUpstreamState upstream) {
        if (head == null) {
            return "UNKNOWN_HEAD";
        }
        if (head.type() != GitHeadType.BRANCH) {
            return "DETACHED_HEAD";
        }
        if (head.commit() == null) {
            return "UNBORN_BRANCH";
        }
        if (conflictState == GitConflictState.CONFLICTED) {
            return "CONFLICTED";
        }
        if (workingTree != GitWorkingTreeState.CLEAN) {
            return "DIRTY_WORKING_TREE";
        }
        if (operationState == GitOperationState.IN_PROGRESS) {
            return "GIT_OPERATION_IN_PROGRESS";
        }
        if (upstream == null) {
            return "NO_UPSTREAM";
        }
        if (upstream.relation() == GitUpstreamRelation.AHEAD) {
            return "AHEAD";
        }
        if (upstream.relation() == GitUpstreamRelation.DIVERGED) {
            return "DIVERGED";
        }
        return null;
    }
}
