package com.sitionix.forgeai.domain.model.agentproxy;

public record AgentProjectRepositoryGitState(
        String branch,
        String workingTree,
        boolean pullAvailable
) {
}
