package com.sitionix.forgeagent.api.dto;

public record ProjectRepositoryGitStateResponse(
        String branch,
        String workingTree,
        boolean pullAvailable
) {
}
