package com.sitionix.forgeai.domain.port;

import com.sitionix.forgeai.domain.model.github.GithubPullRequestCheckResult;
import com.sitionix.forgeai.domain.model.github.GithubRepositoryCheckResult;
import com.sitionix.forgeai.domain.model.github.GithubWorkflowRunCheckResult;

public interface GithubEvidencePort {

    GithubPullRequestCheckResult checkPullRequest(String pullRequestUrl);

    GithubRepositoryCheckResult checkRepository(String repository);

    GithubWorkflowRunCheckResult checkWorkflowRun(String repository, long runId);
}
