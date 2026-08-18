package com.sitionix.forgeai.infrastructure.agentclient.dto;

public record ProjectRepositoryGitHeadResponse(
        String type,
        String ref,
        String commit
) {
}
