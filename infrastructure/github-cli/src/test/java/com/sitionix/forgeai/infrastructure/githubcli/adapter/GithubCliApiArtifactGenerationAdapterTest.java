package com.sitionix.forgeai.infrastructure.githubcli.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.generation.ApiArtifactGenerationRequest;
import com.sitionix.forgeai.domain.model.generation.GeneratedApiArtifact;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GithubCliApiArtifactGenerationAdapterTest {

    @Test
    void givenMavenArtifactRequest_whenGenerate_thenPostGenerateCommentAndReturnArtifact() {
        final FakeRunner runner = new FakeRunner("""
                apis:
                  - name: API Backend for Frontend SOX
                    api-spec-type: api-first
                    definition-path: /bffssox/rest
                """, this.comments("""
                ---
                ### ✅🎉 Deployment Successful! 🎉✅
                ```xml
                <dependency>
                  <groupId>com.afesox</groupId>
                  <artifactId>app-afesox-bffssox-api-first-sitionix-142-unstable</artifactId>
                  <version>0.0.44</version>
                </dependency>
                ```
                🔗 [View Workflow Run](https://github.com/Sitionix/app-afesox/actions/runs/12345)
                ---
                """));
        final GithubCliApiArtifactGenerationAdapter adapter = new GithubCliApiArtifactGenerationAdapter(
                new GithubPullRequestClient(new ObjectMapper(), runner),
                new ApiGenerationMetadataResolver(new ApiGenerationArtifactNaming()),
                new ApiGenerationCommand(),
                new ApiGenerationArtifactParser(new ApiGenerationArtifactNaming()),
                Duration.ofSeconds(1),
                Duration.ZERO
        );

        final GeneratedApiArtifact artifact = adapter.generate(new ApiArtifactGenerationRequest(
                "https://github.com/Sitionix/app-afesox/pull/142",
                "Sitionix/app-afesox",
                "app-afesox-bffssox-api-first-stable",
                "backendforfrontendservice-sox",
                "bffssox",
                "bffssox",
                "api-first"
        ));

        assertThat(runner.commands()).anySatisfy(command -> assertThat(command)
                .containsExactly(
                        "gh",
                        "pr",
                        "comment",
                        "https://github.com/Sitionix/app-afesox/pull/142",
                        "--body",
                        "/generate --name \"API Backend for Frontend SOX\""
                ));
        assertThat(artifact.dependency()).isEqualTo("""
                <dependency>
                  <groupId>com.afesox</groupId>
                  <artifactId>app-afesox-bffssox-api-first-sitionix-142-unstable</artifactId>
                  <version>0.0.44</version>
                </dependency>
                """.trim());
        assertThat(artifact.runId()).isEqualTo(12345L);
    }

    @Test
    void givenFrontendArtifactRequest_whenGenerate_thenReturnNpmPackageArtifact() {
        final FakeRunner runner = new FakeRunner("""
                apis:
                  - name: FE Backend for Frontend SOX
                    api-spec-type: frontend
                    definition-path: /bffssox/rest
                """, this.comments("""
                ---
                ### ✅ Frontend Contract Published
                ```bash
                pnpm add @sitionix/app-afesox-bffssox-frontend-sitionix-142-unstable@0.0.44
                ```
                🔗 [View Workflow Run](https://github.com/Sitionix/app-afesox/actions/runs/222)
                ---
                """));
        final GithubCliApiArtifactGenerationAdapter adapter = new GithubCliApiArtifactGenerationAdapter(
                new GithubPullRequestClient(new ObjectMapper(), runner),
                new ApiGenerationMetadataResolver(new ApiGenerationArtifactNaming()),
                new ApiGenerationCommand(),
                new ApiGenerationArtifactParser(new ApiGenerationArtifactNaming()),
                Duration.ofSeconds(1),
                Duration.ZERO
        );

        final GeneratedApiArtifact artifact = adapter.generate(new ApiArtifactGenerationRequest(
                "https://github.com/Sitionix/app-afesox/pull/142",
                "Sitionix/app-afesox",
                "@sitionix/app-afesox-bffssox-frontend-stable",
                "sitionix-spa",
                "bffssox",
                "bffssox",
                "frontend"
        ));

        assertThat(artifact.dependency()).isEqualTo("pnpm add @sitionix/app-afesox-bffssox-frontend-sitionix-142-unstable@0.0.44");
        assertThat(artifact.runId()).isEqualTo(222L);
    }

    private String comments(final String body) {
        return """
                {
                  "comments": [
                    {
                      "id": "generated",
                      "body": %s
                    }
                  ]
                }
                """.formatted(this.json(body));
    }

    private String json(final String value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (final Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class FakeRunner implements GithubCliCommandRunner {

        private final String metadata;
        private final String generatedComments;
        private final List<List<String>> commands = new ArrayList<>();
        private int commentsCalls;

        private FakeRunner(final String metadata, final String generatedComments) {
            this.metadata = metadata;
            this.generatedComments = generatedComments;
        }

        @Override
        public GithubCliCommandResult run(final List<String> command, final Duration timeout) {
            this.commands.add(List.copyOf(command));
            if (command.contains("headRefName")) {
                return new GithubCliCommandResult(true, "{\"headRefName\":\"feature/SITIONIX-142\"}", "");
            }
            if (command.contains(".content")) {
                return new GithubCliCommandResult(
                        true,
                        Base64.getMimeEncoder().encodeToString(this.metadata.getBytes(StandardCharsets.UTF_8)),
                        ""
                );
            }
            if (command.contains("--comments")) {
                this.commentsCalls++;
                return new GithubCliCommandResult(
                        true,
                        this.commentsCalls == 1 ? "{\"comments\":[]}" : this.generatedComments,
                        ""
                );
            }
            return new GithubCliCommandResult(true, "", "");
        }

        private List<List<String>> commands() {
            return List.copyOf(this.commands);
        }
    }
}
