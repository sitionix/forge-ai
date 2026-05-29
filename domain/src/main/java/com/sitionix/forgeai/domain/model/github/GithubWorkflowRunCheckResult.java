package com.sitionix.forgeai.domain.model.github;

import lombok.Builder;

@Builder
public record GithubWorkflowRunCheckResult(
        Long runId,
        GithubCheckStatus status,
        String conclusion,
        String details
) {
}
