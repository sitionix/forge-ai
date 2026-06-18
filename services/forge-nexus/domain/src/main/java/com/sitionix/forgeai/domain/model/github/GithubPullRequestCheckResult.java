package com.sitionix.forgeai.domain.model.github;

import lombok.Builder;

@Builder
public record GithubPullRequestCheckResult(
        GithubCheckStatus status,
        String details
) {
}
