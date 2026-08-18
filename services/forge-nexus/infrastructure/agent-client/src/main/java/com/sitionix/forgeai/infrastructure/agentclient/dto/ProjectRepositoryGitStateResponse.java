package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectRepositoryGitStateResponse(
        boolean valid,
        ProjectRepositoryGitHeadResponse head,
        String workingTree,
        String conflictState,
        String operationState,
        ProjectRepositoryGitUpstreamResponse upstream,
        boolean pullAllowed,
        String pullBlockedReason
) {
}
