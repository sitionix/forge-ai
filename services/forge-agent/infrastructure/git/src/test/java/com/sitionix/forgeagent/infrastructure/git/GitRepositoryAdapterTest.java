package com.sitionix.forgeagent.infrastructure.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeagent.domain.model.GitHeadType;
import com.sitionix.forgeagent.domain.model.GitWorkingTreeState;
import com.sitionix.forgeagent.domain.port.GitExecutionException;
import com.sitionix.forgeagent.domain.port.GitRemoteRejectedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRepositoryAdapterTest {

    @TempDir
    private Path tempDir;

    @Test
    void inspectsReachableLocalRemote() throws Exception {
        final Path remote = this.createBareRepository("service-a.git");
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(new DefaultGitCommandRunner());

        assertThat(adapter.inspectRemote(remote.toString()).name()).isEqualTo("service-a");
    }

    @Test
    void resolvesRepositoryNameGenerically() {
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter((command, policy) -> new GitCommandResult(0, ""));

        assertThat(adapter.resolveRepositoryName("git@gitlab.com:company/siteservice-sox.git")).isEqualTo("siteservice-sox");
        assertThat(adapter.resolveRepositoryName("https://github.com/company/backend.git")).isEqualTo("backend");
        assertThat(adapter.resolveRepositoryName("ssh://example.com/company/authservice-sox")).isEqualTo("authservice-sox");
    }

    @Test
    void unreachableRemoteFailsInspection() {
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter((command, policy) -> new GitCommandResult(128, "fatal"));

        assertThatThrownBy(() -> adapter.inspectRemote("git@example.com:missing.git"))
                .isInstanceOf(GitRemoteRejectedException.class)
                .hasMessage("Git remote is not reachable.");
    }

    @Test
    void inspectsNormalRepositoryOnBranchAsClean() throws Exception {
        final Path repository = this.createRepositoryWithCommit("service-a");
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(new DefaultGitCommandRunner());

        final var state = adapter.inspectLocalRepository(repository);

        assertThat(state.valid()).isTrue();
        assertThat(state.head().type()).isEqualTo(GitHeadType.BRANCH);
        assertThat(state.head().ref()).isEqualTo("main");
        assertThat(state.head().commit()).isNotBlank();
        assertThat(state.workingTree()).isEqualTo(GitWorkingTreeState.CLEAN);
    }

    @Test
    void inspectsModifiedTrackedFileAsDirty() throws Exception {
        final Path repository = this.createRepositoryWithCommit("service-a");
        Files.writeString(repository.resolve("README.md"), "changed\n");
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(new DefaultGitCommandRunner());

        final var state = adapter.inspectLocalRepository(repository);

        assertThat(state.workingTree()).isEqualTo(GitWorkingTreeState.DIRTY);
    }

    @Test
    void inspectsStagedFileAsDirty() throws Exception {
        final Path repository = this.createRepositoryWithCommit("service-a");
        Files.writeString(repository.resolve("README.md"), "changed\n");
        this.runGit(repository, "git", "add", "README.md");
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(new DefaultGitCommandRunner());

        final var state = adapter.inspectLocalRepository(repository);

        assertThat(state.workingTree()).isEqualTo(GitWorkingTreeState.DIRTY);
    }

    @Test
    void inspectsUntrackedFileAsDirty() throws Exception {
        final Path repository = this.createRepositoryWithCommit("service-a");
        Files.writeString(repository.resolve("new-file.txt"), "untracked\n");
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(new DefaultGitCommandRunner());

        final var state = adapter.inspectLocalRepository(repository);

        assertThat(state.workingTree()).isEqualTo(GitWorkingTreeState.DIRTY);
    }

    @Test
    void inspectsDetachedHeadExplicitly() throws Exception {
        final Path repository = this.createRepositoryWithCommit("service-a");
        this.runGit(repository, "git", "checkout", "--detach", "HEAD");
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(new DefaultGitCommandRunner());

        final var state = adapter.inspectLocalRepository(repository);

        assertThat(state.valid()).isTrue();
        assertThat(state.head().type()).isEqualTo(GitHeadType.DETACHED);
        assertThat(state.head().ref()).isNull();
        assertThat(state.head().commit()).isNotBlank();
        assertThat(state.workingTree()).isEqualTo(GitWorkingTreeState.CLEAN);
    }

    @Test
    void inspectsInvalidNonGitDirectoryWithoutInfrastructureFailure() throws Exception {
        final Path repository = this.tempDir.resolve("not-a-repository");
        Files.createDirectories(repository);
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(new DefaultGitCommandRunner());

        final var state = adapter.inspectLocalRepository(repository);

        assertThat(state.valid()).isFalse();
        assertThat(state.head()).isNull();
        assertThat(state.workingTree()).isNull();
    }

    @Test
    void inspectsRepositoryPathWithSpacesAndSpecialCharacters() throws Exception {
        final Path repository = this.createRepositoryWithCommit("service a [special]");
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(new DefaultGitCommandRunner());

        final var state = adapter.inspectLocalRepository(repository);

        assertThat(state.valid()).isTrue();
        assertThat(state.head().ref()).isEqualTo("main");
        assertThat(state.workingTree()).isEqualTo(GitWorkingTreeState.CLEAN);
    }

    @Test
    void inspectsUnbornRepositoryBranchWithNullCommit() throws Exception {
        final Path repository = this.tempDir.resolve("unborn");
        Files.createDirectories(repository);
        this.runGit(repository, "git", "init", "-b", "main");
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(new DefaultGitCommandRunner());

        final var state = adapter.inspectLocalRepository(repository);

        assertThat(state.valid()).isTrue();
        assertThat(state.head().type()).isEqualTo(GitHeadType.BRANCH);
        assertThat(state.head().ref()).isEqualTo("main");
        assertThat(state.head().commit()).isNull();
        assertThat(state.workingTree()).isEqualTo(GitWorkingTreeState.CLEAN);
    }

    @Test
    void malformedSuccessfulLocalInspectionOutputIsInfrastructureFailure() {
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter((command, policy) -> new GitCommandResult(0, "# branch.head main\n", ""));

        assertThatThrownBy(() -> adapter.inspectLocalRepository(this.tempDir))
                .isInstanceOf(GitExecutionException.class)
                .hasMessage("Git local repository status output is malformed.");
    }

    @Test
    void cloneUsesArgumentBasedGitCommand() {
        final CapturingRunner runner = new CapturingRunner(0);
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(runner);
        final Path target = this.tempDir.resolve("target");

        adapter.clone("https://example.com/company/service.git", target);

        assertThat(runner.commands()).containsExactly(List.of("git", "clone", "https://example.com/company/service.git", target.toString()));
        assertThat(runner.policies()).extracting(GitCommandExecutionPolicy::timeout)
                .containsExactly(Duration.ofMinutes(30));
    }

    @Test
    void remoteUrlIsSingleProcessArgument() {
        final CapturingRunner runner = new CapturingRunner(0);
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(runner);
        final String remoteUrl = "https://example.com/company/service.git;rm -rf /";

        adapter.inspectRemote(remoteUrl);

        assertThat(runner.commands()).containsExactly(List.of("git", "ls-remote", remoteUrl));
        assertThat(runner.policies()).extracting(GitCommandExecutionPolicy::timeout)
                .containsExactly(Duration.ofSeconds(15));
    }

    @Test
    void inspectLocalRepositoryUsesArgumentBasedGitCommand() {
        final CapturingRunner runner = new CapturingRunner(0, """
                # branch.oid abcdef
                # branch.head main
                """);
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(runner);
        final Path repositoryPath = this.tempDir.resolve("repository path");

        adapter.inspectLocalRepository(repositoryPath);

        assertThat(runner.commands()).containsExactly(List.of(
                "git",
                "-C",
                repositoryPath.toString(),
                "status",
                "--porcelain=v2",
                "--branch",
                "--untracked-files=normal"
        ));
        assertThat(runner.policies()).extracting(GitCommandExecutionPolicy::timeout)
                .containsExactly(Duration.ofSeconds(10));
    }

    private Path createBareRepository(final String name) throws Exception {
        final Path remote = this.tempDir.resolve(name);
        Files.createDirectories(remote);
        this.runGit("git", "init", "--bare", remote.toString());
        return remote;
    }

    private Path createRepositoryWithCommit(final String name) throws Exception {
        final Path repository = this.tempDir.resolve(name);
        Files.createDirectories(repository);
        this.runGit(repository, "git", "init", "-b", "main");
        Files.writeString(repository.resolve("README.md"), "initial\n");
        this.runGit(repository, "git", "add", "README.md");
        this.runGit(repository, "git", "-c", "user.name=Forge Test", "-c", "user.email=forge@example.com", "commit", "-m", "Initial commit");
        return repository;
    }

    private void runGit(final String... command) throws IOException, InterruptedException {
        this.runGit(null, command);
    }

    private void runGit(final Path workingDirectory, final String... command) throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(command)
                .directory(workingDirectory == null ? null : workingDirectory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        final int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Git test command failed.");
        }
    }

    private record CapturingRunner(int exitCode,
                                   String stdout,
                                   List<List<String>> commands,
                                   List<GitCommandExecutionPolicy> policies) implements GitCommandRunner {

        CapturingRunner(final int exitCode) {
            this(exitCode, "", new ArrayList<>(), new ArrayList<>());
        }

        CapturingRunner(final int exitCode, final String stdout) {
            this(exitCode, stdout, new ArrayList<>(), new ArrayList<>());
        }

        @Override
        public GitCommandResult run(final List<String> command, final GitCommandExecutionPolicy policy) {
            this.commands.add(List.copyOf(command));
            this.policies.add(policy);
            return new GitCommandResult(this.exitCode, this.stdout, "");
        }
    }
}
