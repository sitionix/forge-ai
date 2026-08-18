package com.sitionix.forgeagent.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectRepositoryGitStateResponse(
        boolean valid,
        ProjectRepositoryGitHeadResponse head,
        String workingTree
) {
}
