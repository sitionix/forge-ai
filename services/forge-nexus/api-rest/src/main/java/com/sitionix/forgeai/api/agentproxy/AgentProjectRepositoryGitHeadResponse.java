package com.sitionix.forgeai.api.agentproxy;

public record AgentProjectRepositoryGitHeadResponse(
        String type,
        String ref,
        String commit
) {
}
