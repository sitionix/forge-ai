package com.sitionix.forgeai.infrastructure.githubcli.adapter;

import com.sitionix.forgeai.domain.model.github.GithubCheckStatus;
import com.sitionix.forgeai.domain.model.github.GithubPullRequestCheckResult;
import com.sitionix.forgeai.domain.model.github.GithubRepositoryCheckResult;
import com.sitionix.forgeai.domain.model.github.GithubWorkflowRunCheckResult;
import com.sitionix.forgeai.domain.port.GithubEvidencePort;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GithubEvidenceCliAdapter implements GithubEvidencePort {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);

    private final GithubCliCommandRunner commandRunner;

    @Override
    public GithubPullRequestCheckResult checkPullRequest(final String pullRequestUrl) {
        if (pullRequestUrl == null || pullRequestUrl.isBlank()) {
            return GithubPullRequestCheckResult.builder()
                    .status(GithubCheckStatus.NOT_FOUND)
                    .details("Pull request URL is empty")
                    .build();
        }
        final GithubCliCommandResult commandResult = this.commandRunner.run(List.of("gh", "pr", "view", pullRequestUrl, "--json", "id"), COMMAND_TIMEOUT);
        if (commandResult.success()) {
            return GithubPullRequestCheckResult.builder()
                    .status(GithubCheckStatus.VERIFIED)
                    .details("Pull request exists")
                    .build();
        }
        if (this.notFound(commandResult)) {
            return GithubPullRequestCheckResult.builder()
                    .status(GithubCheckStatus.NOT_FOUND)
                    .details(commandResult.stderr())
                    .build();
        }
        return GithubPullRequestCheckResult.builder()
                .status(GithubCheckStatus.UNKNOWN)
                .details(commandResult.stderr())
                .build();
    }

    @Override
    public GithubWorkflowRunCheckResult checkWorkflowRun(final String repository, final long runId) {
        if (runId <= 0L) {
            return GithubWorkflowRunCheckResult.builder()
                    .runId(runId)
                    .status(GithubCheckStatus.NOT_FOUND)
                    .details("Workflow run id must be positive")
                    .build();
        }
        if (repository == null || repository.isBlank()) {
            return GithubWorkflowRunCheckResult.builder()
                    .runId(runId)
                    .status(GithubCheckStatus.NOT_FOUND)
                    .details("Repository is empty")
                    .build();
        }
        final GithubCliCommandResult commandResult = this.commandRunner.run(List.of(
                "gh",
                "run",
                "view",
                String.valueOf(runId),
                "--repo",
                repository,
                "--json",
                "databaseId,status,conclusion,url"
        ), COMMAND_TIMEOUT);
        if (commandResult.success()) {
            return GithubWorkflowRunCheckResult.builder()
                    .runId(runId)
                    .status(GithubCheckStatus.VERIFIED)
                    .details("Workflow run exists")
                    .build();
        }
        if (this.notFound(commandResult)) {
            return GithubWorkflowRunCheckResult.builder()
                    .runId(runId)
                    .status(GithubCheckStatus.NOT_FOUND)
                    .details(commandResult.stderr())
                    .build();
        }
        return GithubWorkflowRunCheckResult.builder()
                .runId(runId)
                .status(GithubCheckStatus.UNKNOWN)
                .details(commandResult.stderr())
                .build();
    }

    @Override
    public GithubRepositoryCheckResult checkRepository(final String repository) {
        if (repository == null || repository.isBlank()) {
            return GithubRepositoryCheckResult.builder()
                    .status(GithubCheckStatus.NOT_FOUND)
                    .details("Repository is empty")
                    .build();
        }
        final GithubCliCommandResult commandResult = this.commandRunner.run(List.of("gh", "repo", "view", repository, "--json", "id,nameWithOwner"), COMMAND_TIMEOUT);
        if (commandResult.success()) {
            return GithubRepositoryCheckResult.builder()
                    .status(GithubCheckStatus.VERIFIED)
                    .details("Repository exists")
                    .build();
        }
        if (this.notFound(commandResult)) {
            return GithubRepositoryCheckResult.builder()
                    .status(GithubCheckStatus.NOT_FOUND)
                    .details(commandResult.stderr())
                    .build();
        }
        return GithubRepositoryCheckResult.builder()
                .status(GithubCheckStatus.UNKNOWN)
                .details(commandResult.stderr())
                .build();
    }

    private boolean notFound(final GithubCliCommandResult commandResult) {
        if (commandResult.success()) {
            return false;
        }
        final String normalized = commandResult.stderr() == null ? "" : commandResult.stderr().toLowerCase();
        return normalized.contains("not found") || normalized.contains("404");
    }
}
