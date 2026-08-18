package com.sitionix.forgeai.infrastructure.agentclient.dto;

public record ProjectRepositoryGitStateResponse(
        boolean cloned,
        String branch,
        String workingTree,
        boolean pullAvailable
) {
}
