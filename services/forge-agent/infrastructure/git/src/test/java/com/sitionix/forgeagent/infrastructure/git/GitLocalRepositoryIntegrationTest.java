package com.sitionix.forgeagent.infrastructure.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeagent.domain.model.ProjectRepositoryCloneAttempt;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryWorkspaceReference;
import com.sitionix.forgeagent.domain.port.GitOperationException;
import com.sitionix.forgeagent.infrastructure.local.ForgeRootResolver;
import com.sitionix.forgeagent.infrastructure.local.LocalProjectWorkspaceAdapter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitLocalRepositoryIntegrationTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID REPOSITORY_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @TempDir
    private Path tempDir;

    @Test
    void realGitAdapterClonesLocalBareRemoteIntoLocalWorkspace() throws Exception {
        final Path forgeRoot = this.forgeRoot();
        final Path remote = this.createBareRepository("service-a.git");
        final GitRepositoryAdapter git = new GitRepositoryAdapter(new DefaultGitCommandRunner());
        final LocalProjectWorkspaceAdapter local = new LocalProjectWorkspaceAdapter(
                new ForgeRootResolver(forgeRoot.resolve("services/forge-agent"))
        );

        assertThat(git.inspectRemote(remote.toString()).name()).isEqualTo("service-a");
        assertThat(git.resolveRepositoryName(remote.toString())).isEqualTo("service-a");

        final ProjectRepositoryWorkspaceReference reference = new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a");
        final ProjectRepositoryCloneAttempt attempt = local.prepareCloneAttempt(PROJECT_ID, reference);
        git.clone(remote.toString(), attempt.stagingPath());
        local.finalizeCloneAttempt(attempt);

        assertThat(attempt.stagingPath()).doesNotExist();
        assertThat(attempt.finalPath()).isDirectory();
        assertThat(attempt.finalPath().resolve(".git")).isDirectory();
        assertThat(local.resolveCloneStates(PROJECT_ID, List.of(reference))).containsEntry(REPOSITORY_ID, true);
    }

    @Test
    void realFailedCloneCanBeCleanedSoLocalStateIsNotCloned() throws Exception {
        final Path forgeRoot = this.forgeRoot();
        final GitRepositoryAdapter git = new GitRepositoryAdapter(new DefaultGitCommandRunner());
        final LocalProjectWorkspaceAdapter local = new LocalProjectWorkspaceAdapter(new ForgeRootResolver(forgeRoot));
        final ProjectRepositoryWorkspaceReference reference = new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "missing");
        final ProjectRepositoryCloneAttempt attempt = local.prepareCloneAttempt(PROJECT_ID, reference);

        assertThatThrownBy(() -> git.clone(this.tempDir.resolve("missing.git").toString(), attempt.stagingPath()))
                .isInstanceOf(GitOperationException.class)
                .hasMessage("Git clone failed.");
        Files.createDirectories(attempt.stagingPath().resolve(".git"));

        local.cleanupCloneAttempt(attempt);

        final Map<UUID, Boolean> states = local.resolveCloneStates(PROJECT_ID, List.of(reference));
        assertThat(states).containsEntry(REPOSITORY_ID, false);
        assertThat(attempt.stagingPath()).doesNotExist();
        assertThat(attempt.finalPath()).doesNotExist();
    }

    private Path forgeRoot() throws IOException {
        final Path forgeRoot = this.tempDir.resolve(UUID.randomUUID().toString()).resolve("forge-ai");
        Files.createDirectories(forgeRoot.resolve(".git"));
        return forgeRoot;
    }

    private Path createBareRepository(final String name) throws Exception {
        final Path remote = this.tempDir.resolve(name);
        Files.createDirectories(remote);
        this.runGit("git", "init", "--bare", remote.toString());
        return remote;
    }

    private void runGit(final String... command) throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        final int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Git test command failed.");
        }
    }
}
