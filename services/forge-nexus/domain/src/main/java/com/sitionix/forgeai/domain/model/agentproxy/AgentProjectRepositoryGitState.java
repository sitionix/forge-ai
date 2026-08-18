package com.sitionix.forgeai.domain.model.agentproxy;

public record AgentProjectRepositoryGitState(
        boolean valid,
        AgentProjectRepositoryGitHead head,
        AgentProjectRepositoryWorkingTreeState workingTree
) {
}
