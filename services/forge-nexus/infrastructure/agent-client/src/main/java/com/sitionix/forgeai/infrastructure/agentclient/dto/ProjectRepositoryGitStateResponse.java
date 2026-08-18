package com.sitionix.forgeai.infrastructure.agentclient.dto;

public record ProjectRepositoryGitStateResponse(
        String branch,
        String workingTree,
        boolean pullAvailable
) {
}
