package com.sitionix.forgeai.domain.model.agentproxy;

public record AgentProjectRepositoryGitState(
        boolean cloned,
        String branch,
        AgentProjectRepositoryWorkingTreeState workingTree,
        boolean pullAvailable
) {
}
