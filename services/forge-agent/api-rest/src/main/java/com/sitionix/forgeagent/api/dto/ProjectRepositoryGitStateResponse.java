package com.sitionix.forgeagent.api.dto;

public record ProjectRepositoryGitStateResponse(
        boolean cloned,
        String branch,
        String workingTree,
        boolean pullAvailable
) {
}
