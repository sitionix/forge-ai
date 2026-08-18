package com.sitionix.forgeai.api.agentproxy;

public record AgentProjectRepositoryGitStateResponse(
        String branch,
        String workingTree,
        boolean pullAvailable
) {
}
