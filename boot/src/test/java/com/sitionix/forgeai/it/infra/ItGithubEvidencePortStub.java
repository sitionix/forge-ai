package com.sitionix.forgeai.it.infra;

import com.sitionix.forgeai.domain.model.github.GithubCheckStatus;
import com.sitionix.forgeai.domain.model.github.GithubPullRequestCheckResult;
import com.sitionix.forgeai.domain.model.github.GithubRepositoryCheckResult;
import com.sitionix.forgeai.domain.model.github.GithubWorkflowRunCheckResult;
import com.sitionix.forgeai.domain.port.GithubEvidencePort;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Primary
@Profile("it")
public class ItGithubEvidencePortStub implements GithubEvidencePort {

    @Override
    public GithubPullRequestCheckResult checkPullRequest(final String pullRequestUrl) {
        return GithubPullRequestCheckResult.builder()
                .status(GithubCheckStatus.VERIFIED)
                .details("IT stub")
                .build();
    }

    @Override
    public GithubWorkflowRunCheckResult checkWorkflowRun(final String repository, final long runId) {
        return GithubWorkflowRunCheckResult.builder()
                .runId(runId)
                .status(GithubCheckStatus.VERIFIED)
                .details("IT stub")
                .build();
    }

    @Override
    public GithubRepositoryCheckResult checkRepository(final String repository) {
        return GithubRepositoryCheckResult.builder()
                .status(GithubCheckStatus.VERIFIED)
                .details("IT stub")
                .build();
    }
}
