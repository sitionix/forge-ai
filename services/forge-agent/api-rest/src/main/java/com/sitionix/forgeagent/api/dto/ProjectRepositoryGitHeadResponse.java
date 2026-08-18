package com.sitionix.forgeagent.api.dto;

public record ProjectRepositoryGitHeadResponse(
        String type,
        String ref,
        String commit
) {
}
