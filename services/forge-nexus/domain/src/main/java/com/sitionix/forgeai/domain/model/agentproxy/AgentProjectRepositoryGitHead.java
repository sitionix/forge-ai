package com.sitionix.forgeai.domain.model.agentproxy;

public record AgentProjectRepositoryGitHead(
        AgentProjectRepositoryGitHeadType type,
        String ref,
        String commit
) {
}
