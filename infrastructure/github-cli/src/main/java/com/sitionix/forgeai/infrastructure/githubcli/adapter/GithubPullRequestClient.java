package com.sitionix.forgeai.infrastructure.githubcli.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
class GithubPullRequestClient {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    private final ObjectMapper objectMapper;
    private final GithubCliCommandRunner commandRunner;

    GithubPullRequestClient(final ObjectMapper objectMapper, final GithubCliCommandRunner commandRunner) {
        this.objectMapper = objectMapper;
        this.commandRunner = commandRunner;
    }

    String headRefName(final String pullRequestUrl) {
        final GithubCliCommandResult result = this.requireSuccess(List.of(
                "gh",
                "pr",
                "view",
                pullRequestUrl,
                "--json",
                "headRefName"
        ));
        try {
            return this.objectMapper.readTree(result.stdout()).path("headRefName").asText();
        } catch (final IOException exception) {
            throw new IllegalStateException("Failed to parse gh pr view output", exception);
        }
    }

    String fileContent(final String repository, final String path, final String ref) {
        final GithubCliCommandResult result = this.requireSuccess(List.of(
                "gh",
                "api",
                "repos/" + repository + "/contents/" + path + "?ref=" + ref,
                "--jq",
                ".content"
        ));
        return new String(Base64.getMimeDecoder().decode(result.stdout()), StandardCharsets.UTF_8);
    }

    Set<String> commentIds(final String pullRequestUrl) {
        return this.comments(pullRequestUrl).stream()
                .map(GithubPullRequestComment::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    List<GithubPullRequestComment> comments(final String pullRequestUrl) {
        final GithubCliCommandResult result = this.requireSuccess(List.of(
                "gh",
                "pr",
                "view",
                pullRequestUrl,
                "--comments",
                "--json",
                "comments"
        ));
        try {
            final JsonNode comments = this.objectMapper.readTree(result.stdout()).path("comments");
            final List<GithubPullRequestComment> views = new ArrayList<>();
            for (final JsonNode comment : comments) {
                final String id = comment.path("id").asText(comment.path("url").asText(comment.path("createdAt").asText()));
                views.add(new GithubPullRequestComment(id, comment.path("body").asText("")));
            }
            return views;
        } catch (final IOException exception) {
            throw new IllegalStateException("Failed to parse gh pr comments output", exception);
        }
    }

    void postComment(final String pullRequestUrl, final String body) {
        this.requireSuccess(List.of(
                "gh",
                "pr",
                "comment",
                pullRequestUrl,
                "--body",
                body
        ));
    }

    private GithubCliCommandResult requireSuccess(final List<String> command) {
        final GithubCliCommandResult result = this.commandRunner.run(command, COMMAND_TIMEOUT);
        if (!result.success()) {
            throw new IllegalStateException("GitHub CLI command failed: " + String.join(" ", command) + "\n" + result.stderr());
        }
        return result;
    }
}
