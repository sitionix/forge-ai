package com.sitionix.forgeai.domain.model.agentproxy;

public record AgentProjectRepositoryGitState(
        boolean valid,
        AgentProjectRepositoryGitHead head,
        AgentProjectRepositoryWorkingTreeState workingTree,
        AgentProjectRepositoryConflictState conflictState,
        AgentProjectRepositoryOperationState operationState,
        AgentProjectRepositoryUpstream upstream,
        boolean pullAllowed,
        String pullBlockedReason,
        boolean checkUpdatesAllowed,
        String checkUpdatesBlockedReason
) {
}
