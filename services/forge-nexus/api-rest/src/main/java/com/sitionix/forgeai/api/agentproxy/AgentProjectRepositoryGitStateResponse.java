package com.sitionix.forgeai.api.agentproxy;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentProjectRepositoryGitStateResponse(
        boolean valid,
        AgentProjectRepositoryGitHeadResponse head,
        String workingTree,
        String conflictState,
        String operationState,
        AgentProjectRepositoryGitUpstreamResponse upstream,
        boolean pullAllowed,
        String pullBlockedReason,
        boolean checkUpdatesAllowed,
        String checkUpdatesBlockedReason
) {
}
