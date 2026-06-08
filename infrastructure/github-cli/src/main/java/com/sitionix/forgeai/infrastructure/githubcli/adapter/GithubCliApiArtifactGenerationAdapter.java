package com.sitionix.forgeai.infrastructure.githubcli.adapter;

import com.sitionix.forgeai.domain.model.generation.ApiArtifactGenerationRequest;
import com.sitionix.forgeai.domain.model.generation.GeneratedApiArtifact;
import com.sitionix.forgeai.domain.port.ApiArtifactGenerationPort;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class GithubCliApiArtifactGenerationAdapter implements ApiArtifactGenerationPort {

    private static final Duration POLL_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(15);

    private final GithubPullRequestClient pullRequestClient;
    private final ApiGenerationMetadataResolver metadataResolver;
    private final ApiGenerationCommand generationCommand;
    private final ApiGenerationArtifactParser artifactParser;
    private final Duration pollTimeout;
    private final Duration pollInterval;

    @Autowired
    public GithubCliApiArtifactGenerationAdapter(final GithubPullRequestClient pullRequestClient,
                                                 final ApiGenerationMetadataResolver metadataResolver,
                                                 final ApiGenerationCommand generationCommand,
                                                 final ApiGenerationArtifactParser artifactParser) {
        this(pullRequestClient, metadataResolver, generationCommand, artifactParser, POLL_TIMEOUT, POLL_INTERVAL);
    }

    GithubCliApiArtifactGenerationAdapter(final GithubPullRequestClient pullRequestClient,
                                          final ApiGenerationMetadataResolver metadataResolver,
                                          final ApiGenerationCommand generationCommand,
                                          final ApiGenerationArtifactParser artifactParser,
                                          final Duration pollTimeout,
                                          final Duration pollInterval) {
        this.pullRequestClient = pullRequestClient;
        this.metadataResolver = metadataResolver;
        this.generationCommand = generationCommand;
        this.artifactParser = artifactParser;
        this.pollTimeout = pollTimeout;
        this.pollInterval = pollInterval;
    }

    @Override
    public GeneratedApiArtifact generate(final ApiArtifactGenerationRequest request) {
        this.validate(request);
        final String headRefName = this.pullRequestClient.headRefName(request.pullRequestUrl());
        final String metadata = this.pullRequestClient.fileContent(request.repository(), "apis/metadata.yml", headRefName);
        final String generationName = this.metadataResolver.resolveGenerationName(request, metadata);
        final Set<String> existingCommentIds = this.pullRequestClient.commentIds(request.pullRequestUrl());
        this.pullRequestClient.postComment(request.pullRequestUrl(), this.generationCommand.body(generationName));
        return this.awaitArtifact(request, generationName, existingCommentIds);
    }

    private GeneratedApiArtifact awaitArtifact(final ApiArtifactGenerationRequest request,
                                               final String generationName,
                                               final Set<String> existingCommentIds) {
        final Instant deadline = Instant.now().plus(this.pollTimeout);
        while (Instant.now().isBefore(deadline)) {
            final List<GithubPullRequestComment> comments = this.pullRequestClient.comments(request.pullRequestUrl()).stream()
                    .filter(comment -> !existingCommentIds.contains(comment.id()))
                    .toList();
            for (int index = comments.size() - 1; index >= 0; index--) {
                final GithubPullRequestComment comment = comments.get(index);
                if (this.artifactParser.isFailure(comment.body())) {
                    throw new IllegalStateException("API artifact generation failed for " + generationName + ": "
                            + this.artifactParser.compact(comment.body()));
                }
                final GeneratedApiArtifact artifact = this.artifactParser.parse(request, generationName, comment.body());
                if (artifact != null) {
                    return artifact;
                }
            }
            this.sleep();
        }
        throw new IllegalStateException("Timed out waiting for API artifact generation: name=" + generationName
                + ", expectedArtifact=" + request.expectedArtifact());
    }

    private void validate(final ApiArtifactGenerationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("API artifact generation request is required");
        }
        if (this.isBlank(request.pullRequestUrl())) {
            throw new IllegalArgumentException("Pull request URL is required for API artifact generation");
        }
        if (this.isBlank(request.repository())) {
            throw new IllegalArgumentException("Repository is required for API artifact generation");
        }
        if (this.isBlank(request.expectedArtifact())) {
            throw new IllegalArgumentException("Expected artifact is required for API artifact generation");
        }
    }

    private boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }

    private void sleep() {
        try {
            Thread.sleep(this.pollInterval.toMillis());
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for API artifact generation", exception);
        }
    }
}
