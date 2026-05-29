package com.sitionix.forgeai.infrastructure.githubcli.adapter;

import com.sitionix.forgeai.domain.model.github.GithubCheckStatus;
import com.sitionix.forgeai.domain.model.github.GithubPullRequestCheckResult;
import com.sitionix.forgeai.domain.model.github.GithubRepositoryCheckResult;
import com.sitionix.forgeai.domain.model.github.GithubWorkflowRunCheckResult;
import com.sitionix.forgeai.domain.port.GithubEvidencePort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class GithubEvidenceCliAdapter implements GithubEvidencePort {

    @Override
    public GithubPullRequestCheckResult checkPullRequest(final String pullRequestUrl) {
        if (pullRequestUrl == null || pullRequestUrl.isBlank()) {
            return GithubPullRequestCheckResult.builder()
                    .status(GithubCheckStatus.NOT_FOUND)
                    .details("Pull request URL is empty")
                    .build();
        }
        final CommandResult commandResult = this.run(List.of("gh", "pr", "view", pullRequestUrl, "--json", "id"));
        if (commandResult.success()) {
            return GithubPullRequestCheckResult.builder()
                    .status(GithubCheckStatus.VERIFIED)
                    .details("Pull request exists")
                    .build();
        }
        if (commandResult.notFound()) {
            return GithubPullRequestCheckResult.builder()
                    .status(GithubCheckStatus.NOT_FOUND)
                    .details(commandResult.error())
                    .build();
        }
        return GithubPullRequestCheckResult.builder()
                .status(GithubCheckStatus.UNKNOWN)
                .details(commandResult.error())
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
        final CommandResult commandResult = this.run(List.of(
                "gh",
                "run",
                "view",
                String.valueOf(runId),
                "--repo",
                repository,
                "--json",
                "databaseId,status,conclusion,url"
        ));
        if (commandResult.success()) {
            return GithubWorkflowRunCheckResult.builder()
                    .runId(runId)
                    .status(GithubCheckStatus.VERIFIED)
                    .details("Workflow run exists")
                    .build();
        }
        if (commandResult.notFound()) {
            return GithubWorkflowRunCheckResult.builder()
                    .runId(runId)
                    .status(GithubCheckStatus.NOT_FOUND)
                    .details(commandResult.error())
                    .build();
        }
        return GithubWorkflowRunCheckResult.builder()
                .runId(runId)
                .status(GithubCheckStatus.UNKNOWN)
                .details(commandResult.error())
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
        final CommandResult commandResult = this.run(List.of("gh", "repo", "view", repository, "--json", "id,nameWithOwner"));
        if (commandResult.success()) {
            return GithubRepositoryCheckResult.builder()
                    .status(GithubCheckStatus.VERIFIED)
                    .details("Repository exists")
                    .build();
        }
        if (commandResult.notFound()) {
            return GithubRepositoryCheckResult.builder()
                    .status(GithubCheckStatus.NOT_FOUND)
                    .details(commandResult.error())
                    .build();
        }
        return GithubRepositoryCheckResult.builder()
                .status(GithubCheckStatus.UNKNOWN)
                .details(commandResult.error())
                .build();
    }

    private CommandResult run(final List<String> command) {
        final ProcessBuilder processBuilder = new ProcessBuilder(command);
        try {
            final Process process = processBuilder.start();
            final int code = process.waitFor();
            final String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return new CommandResult(code == 0, stderr);
        } catch (final IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new CommandResult(false, exception.getMessage());
        }
    }

    private record CommandResult(boolean success, String error) {

        private boolean notFound() {
            if (this.success) {
                return false;
            }
            final String normalized = this.error == null ? "" : this.error.toLowerCase();
            return normalized.contains("not found") || normalized.contains("404");
        }
    }
}
