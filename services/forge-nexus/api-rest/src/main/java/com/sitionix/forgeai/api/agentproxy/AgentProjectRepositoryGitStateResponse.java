package com.sitionix.forgeai.api.agentproxy;

public record AgentProjectRepositoryGitStateResponse(
        boolean cloned,
        String branch,
        String workingTree,
        boolean pullAvailable
) {
}
