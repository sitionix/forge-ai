package com.sitionix.forgeai.domain.model.github;

import lombok.Builder;

@Builder
public record GithubRepositoryCheckResult(
        GithubCheckStatus status,
        String details
) {
}
