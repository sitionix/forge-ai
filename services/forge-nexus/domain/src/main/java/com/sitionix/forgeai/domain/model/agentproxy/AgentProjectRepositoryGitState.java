package com.sitionix.forgeai.domain.model.agentproxy;

public record AgentProjectRepositoryGitState(
        String branch,
        AgentProjectRepositoryWorkingTreeState workingTree,
        boolean pullAvailable
) {
}
