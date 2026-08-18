package com.sitionix.forgeagent.infrastructure.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeagent.domain.model.GitHeadType;
import com.sitionix.forgeagent.domain.model.GitConflictState;
import com.sitionix.forgeagent.domain.model.GitOperationState;
import com.sitionix.forgeagent.domain.model.GitUpstreamRelation;
import com.sitionix.forgeagent.domain.model.GitWorkingTreeState;
import com.sitionix.forgeagent.domain.port.GitExecutionException;
import com.sitionix.forgeagent.domain.port.GitRemoteRejectedException;
import com.sitionix.forgeagent.domain.port.GitUnsafeRepositoryStateException;
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
        assertThat(state.conflictState()).isEqualTo(GitConflictState.NONE);
        assertThat(state.operationState()).isEqualTo(GitOperationState.NORMAL);
        assertThat(state.upstream()).isNull();
        assertThat(state.pullAllowed()).isFalse();
        assertThat(state.pullBlockedReason()).isEqualTo("NO_UPSTREAM");
    }

    @Test
    void inspectsGitFileWorktreeCheckoutAsValid() throws Exception {
        final Path repository = this.createRepositoryWithCommit("service-a");
        final Path worktree = this.tempDir.resolve("service-a-worktree");
        this.runGit(repository, "git", "worktree", "add", "--detach", worktree.toString(), "HEAD");
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(new DefaultGitCommandRunner());

        final var state = adapter.inspectLocalRepository(worktree);

        assertThat(Files.isRegularFile(worktree.resolve(".git"))).isTrue();
        assertThat(state.valid()).isTrue();
        assertThat(state.head().type()).isEqualTo(GitHeadType.DETACHED);
        assertThat(state.head().ref()).isNull();
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
        assertThat(state.pullAllowed()).isFalse();
        assertThat(state.pullBlockedReason()).isEqualTo("DIRTY_WORKING_TREE");
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
        assertThat(state.pullAllowed()).isFalse();
        assertThat(state.pullBlockedReason()).isEqualTo("DETACHED_HEAD");
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
    void malformedGitFileIsInvalidWithoutInfrastructureFailure() throws Exception {
        final Path repository = this.tempDir.resolve("malformed-git-file");
        Files.createDirectories(repository);
        Files.writeString(repository.resolve(".git"), "gitdir: missing\n");
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(new DefaultGitCommandRunner());

        final var state = adapter.inspectLocalRepository(repository);

        assertThat(state.valid()).isFalse();
        assertThat(state.head()).isNull();
        assertThat(state.workingTree()).isNull();
    }

    @Test
    void malformedGitDirectoryIsInvalidWithoutInfrastructureFailure() throws Exception {
        final Path repository = this.tempDir.resolve("malformed-git-directory");
        Files.createDirectories(repository.resolve(".git"));
        Files.writeString(repository.resolve(".git").resolve("HEAD"), "ref: refs/heads/main\n");
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(new DefaultGitCommandRunner());

        final var state = adapter.inspectLocalRepository(repository);

        assertThat(state.valid()).isFalse();
        assertThat(state.head()).isNull();
        assertThat(state.workingTree()).isNull();
    }

    @Test
    void nestedBrokenChildCheckoutDoesNotFallBackToParentRepositoryState() throws Exception {
        final Path parent = this.createRepositoryWithCommit("forge-source-parent");
        final Path child = parent.resolve("forge-projects/project-id/service-a");
        Files.createDirectories(child.resolve(".git"));
        Files.writeString(child.resolve(".git").resolve("HEAD"), "ref: refs/heads/main\n");
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(new DefaultGitCommandRunner());

        final var state = adapter.inspectLocalRepository(child);

        assertThat(state.valid()).isFalse();
        assertThat(state.head()).isNull();
        assertThat(state.workingTree()).isNull();
    }

    @Test
    void nestedDirectoryWithoutOwnGitRootDoesNotReturnParentRepositoryState() throws Exception {
        final Path parent = this.createRepositoryWithCommit("forge-source-parent");
        final Path child = parent.resolve("forge-projects/project-id/service-a");
        Files.createDirectories(child);
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(new DefaultGitCommandRunner());

        final var state = adapter.inspectLocalRepository(child);

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
        assertThat(state.pullAllowed()).isFalse();
        assertThat(state.pullBlockedReason()).isEqualTo("UNBORN_BRANCH");
    }

    @Test
    void inspectsTrackingBranchBehindRemoteAsPullAllowedAndFastForwards() throws Exception {
        final RemoteBackedRepository repositories = this.createRemoteBackedRepository("service-a");
        final String before = this.runGitOutput(repositories.local(), "git", "rev-parse", "HEAD");
        this.commitAndPush(repositories.writer(), "remote\n", "Remote update");
        this.runGit(repositories.local(), "git", "fetch", "origin");
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(new DefaultGitCommandRunner());

        final var state = adapter.inspectLocalRepository(repositories.local());
        final var result = adapter.pullFastForward(repositories.local());

        assertThat(state.upstream().ref()).isEqualTo("origin/main");
        assertThat(state.upstream().relation()).isEqualTo(GitUpstreamRelation.BEHIND);
        assertThat(state.pullAllowed()).isTrue();
        assertThat(result.upstream().relation()).isEqualTo(GitUpstreamRelation.UP_TO_DATE);
        assertThat(result.pullAllowed()).isTrue();
        assertThat(this.runGitOutput(repositories.local(), "git", "rev-parse", "HEAD")).isNotEqualTo(before);
    }

    @Test
    void pullFastForwardSucceedsAsNoOpWhenAlreadyUpToDate() throws Exception {
        final RemoteBackedRepository repositories = this.createRemoteBackedRepository("service-a");
        final String before = this.runGitOutput(repositories.local(), "git", "rev-parse", "HEAD");
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(new DefaultGitCommandRunner());

        final var result = adapter.pullFastForward(repositories.local());

        assertThat(result.upstream().relation()).isEqualTo(GitUpstreamRelation.UP_TO_DATE);
        assertThat(this.runGitOutput(repositories.local(), "git", "rev-parse", "HEAD")).isEqualTo(before);
    }

    @Test
    void pullFastForwardBlocksDirtyRepositoryBeforeFetch() throws Exception {
        final RemoteBackedRepository repositories = this.createRemoteBackedRepository("service-a");
        Files.writeString(repositories.local().resolve("README.md"), "dirty\n");
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(new DefaultGitCommandRunner());

        assertThatThrownBy(() -> adapter.pullFastForward(repositories.local()))
                .isInstanceOf(GitUnsafeRepositoryStateException.class);
    }

    @Test
    void inspectsUnresolvedConflictAndBlocksPull() throws Exception {
        final RemoteBackedRepository repositories = this.createRemoteBackedRepository("service-a");
        this.commitAndPush(repositories.writer(), "remote\n", "Remote update");
        this.commit(repositories.local(), "local\n", "Local update");
        this.runGit(repositories.local(), "git", "fetch", "origin");
        this.runGitAllowFailure(repositories.local(), "git", "merge", "origin/main");
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(new DefaultGitCommandRunner());

        final var state = adapter.inspectLocalRepository(repositories.local());

        assertThat(state.conflictState()).isEqualTo(GitConflictState.CONFLICTED);
        assertThat(state.operationState()).isEqualTo(GitOperationState.IN_PROGRESS);
        assertThat(state.pullAllowed()).isFalse();
        assertThat(state.pullBlockedReason()).isEqualTo("CONFLICTED");
        assertThatThrownBy(() -> adapter.pullFastForward(repositories.local()))
                .isInstanceOf(GitUnsafeRepositoryStateException.class);
    }

    @Test
    void pullFastForwardBlocksLocalAheadRepository() throws Exception {
        final RemoteBackedRepository repositories = this.createRemoteBackedRepository("service-a");
        this.commit(repositories.local(), "local\n", "Local update");
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(new DefaultGitCommandRunner());

        final var state = adapter.inspectLocalRepository(repositories.local());

        assertThat(state.upstream().relation()).isEqualTo(GitUpstreamRelation.AHEAD);
        assertThat(state.pullAllowed()).isFalse();
        assertThatThrownBy(() -> adapter.pullFastForward(repositories.local()))
                .isInstanceOf(GitUnsafeRepositoryStateException.class);
    }

    @Test
    void pullFastForwardBlocksDivergedRepositoryWithoutMergeCommitOrRebase() throws Exception {
        final RemoteBackedRepository repositories = this.createRemoteBackedRepository("service-a");
        this.commitAndPush(repositories.writer(), "remote\n", "Remote update");
        this.commit(repositories.local(), "local\n", "Local update");
        final String before = this.runGitOutput(repositories.local(), "git", "rev-parse", "HEAD");
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(new DefaultGitCommandRunner());

        assertThatThrownBy(() -> adapter.pullFastForward(repositories.local()))
                .isInstanceOf(GitUnsafeRepositoryStateException.class);

        assertThat(this.runGitOutput(repositories.local(), "git", "rev-parse", "HEAD")).isEqualTo(before);
        assertThat(this.runGitOutput(repositories.local(), "git", "rev-list", "--parents", "-n", "1", "HEAD").split(" ")).hasSize(2);
        assertThat(this.runGitAllowFailure(repositories.local(), "git", "rebase", "--show-current-patch")).isNotZero();
    }

    @Test
    void pullFastForwardSupportsRepositoryPathWithSpacesAndSpecialCharacters() throws Exception {
        final RemoteBackedRepository repositories = this.createRemoteBackedRepository("service a [special]");
        this.commitAndPush(repositories.writer(), "remote\n", "Remote update");
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(new DefaultGitCommandRunner());

        final var result = adapter.pullFastForward(repositories.local());

        assertThat(result.valid()).isTrue();
        assertThat(result.upstream().relation()).isEqualTo(GitUpstreamRelation.UP_TO_DATE);
    }

    @Test
    void pullFastForwardRevalidatesAfterFetchAndAbortsIfCheckoutBecomesDirty() throws Exception {
        final RemoteBackedRepository repositories = this.createRemoteBackedRepository("service-a");
        this.commitAndPush(repositories.writer(), "remote\n", "Remote update");
        final String before = this.runGitOutput(repositories.local(), "git", "rev-parse", "HEAD");
        final MutatingGitCommandRunner runner = new MutatingGitCommandRunner(repositories.local());
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(runner);

        assertThatThrownBy(() -> adapter.pullFastForward(repositories.local()))
                .isInstanceOf(GitUnsafeRepositoryStateException.class);

        assertThat(this.runGitOutput(repositories.local(), "git", "rev-parse", "HEAD")).isEqualTo(before);
        assertThat(runner.commands().stream()).noneMatch(command -> command.contains("merge"));
    }

    @Test
    void malformedSuccessfulLocalInspectionOutputIsInfrastructureFailure() {
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(new CapturingRunner(
                new GitCommandResult(0, this.tempDir.toString() + "\n", ""),
                new GitCommandResult(0, "# branch.head main\n", "")
        ));

        assertThatThrownBy(() -> adapter.inspectLocalRepository(this.tempDir))
                .isInstanceOf(GitExecutionException.class)
                .hasMessage("Git local repository status output is malformed.");
    }

    @Test
    void localStatusFailureAfterPositiveRootIsInfrastructureFailure() {
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(new CapturingRunner(
                new GitCommandResult(0, this.tempDir.toString() + "\n", ""),
                new GitCommandResult(1, "", "")
        ));

        assertThatThrownBy(() -> adapter.inspectLocalRepository(this.tempDir))
                .isInstanceOf(GitExecutionException.class)
                .hasMessage("Git local repository inspection failed.");
    }

    @Test
    void localGitRunnerFailureRemainsInfrastructureFailure() {
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter((command, policy) -> {
            throw new GitExecutionException("git executable missing");
        });

        assertThatThrownBy(() -> adapter.inspectLocalRepository(this.tempDir))
                .isInstanceOf(GitExecutionException.class)
                .hasMessage("git executable missing");
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
        final Path repositoryPath = this.tempDir.resolve("repository path");
        assertThatCode(() -> Files.createDirectories(repositoryPath)).doesNotThrowAnyException();
        final CapturingRunner runner = new CapturingRunner(
                new GitCommandResult(0, repositoryPath.toString() + "\n", ""),
                new GitCommandResult(0, """
                # branch.oid abcdef
                # branch.head main
                """, "")
        );
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(runner);

        adapter.inspectLocalRepository(repositoryPath);

        assertThat(runner.commands()).containsExactly(List.of(
                "git",
                "-C",
                repositoryPath.toString(),
                "rev-parse",
                "--show-toplevel"
        ), List.of(
                "git",
                "-C",
                repositoryPath.toString(),
                "status",
                "--porcelain=v2",
                "--branch",
                "--untracked-files=normal"
        ), List.of(
                "git",
                "-C",
                repositoryPath.toString(),
                "rev-parse",
                "--git-path",
                "MERGE_HEAD",
                "--git-path",
                "CHERRY_PICK_HEAD",
                "--git-path",
                "REBASE_HEAD",
                "--git-path",
                "rebase-merge",
                "--git-path",
                "rebase-apply"
        ));
        assertThat(runner.policies()).extracting(GitCommandExecutionPolicy::timeout)
                .containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10));
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

    private RemoteBackedRepository createRemoteBackedRepository(final String name) throws Exception {
        final Path seed = this.createRepositoryWithCommit(name + "-seed");
        final Path remote = this.createBareRepository(name + ".git");
        this.runGit(seed, "git", "remote", "add", "origin", remote.toString());
        this.runGit(seed, "git", "push", "-u", "origin", "main");
        final Path local = this.tempDir.resolve(name + "-local");
        final Path writer = this.tempDir.resolve(name + "-writer");
        this.runGit("git", "clone", remote.toString(), local.toString());
        this.runGit("git", "clone", remote.toString(), writer.toString());
        return new RemoteBackedRepository(remote, local, writer);
    }

    private void commitAndPush(final Path repository, final String content, final String message) throws Exception {
        this.commit(repository, content, message);
        this.runGit(repository, "git", "push", "origin", "main");
    }

    private void commit(final Path repository, final String content, final String message) throws Exception {
        Files.writeString(repository.resolve("README.md"), content);
        this.runGit(repository, "git", "add", "README.md");
        this.runGit(repository, "git", "-c", "user.name=Forge Test", "-c", "user.email=forge@example.com", "commit", "-m", message);
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

    private int runGitAllowFailure(final Path workingDirectory, final String... command) throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(command)
                .directory(workingDirectory == null ? null : workingDirectory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        return process.waitFor();
    }

    private String runGitOutput(final Path workingDirectory, final String... command) throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(command)
                .directory(workingDirectory == null ? null : workingDirectory.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        final String output = new String(process.getInputStream().readAllBytes()).trim();
        final int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Git test command failed.");
        }
        return output;
    }

    private record RemoteBackedRepository(Path remote, Path local, Path writer) {
    }

    private static final class MutatingGitCommandRunner implements GitCommandRunner {

        private final DefaultGitCommandRunner delegate = new DefaultGitCommandRunner();
        private final Path repositoryPath;
        private final List<List<String>> commands = new ArrayList<>();
        private boolean mutated;

        private MutatingGitCommandRunner(final Path repositoryPath) {
            this.repositoryPath = repositoryPath;
        }

        @Override
        public GitCommandResult run(final List<String> command, final GitCommandExecutionPolicy policy) {
            this.commands.add(List.copyOf(command));
            final GitCommandResult result = this.delegate.run(command, policy);
            if (!this.mutated && command.contains("fetch")) {
                try {
                    Files.writeString(this.repositoryPath.resolve("race.txt"), "dirty\n");
                    this.mutated = true;
                } catch (final IOException exception) {
                    throw new GitExecutionException("Race mutation failed.", exception);
                }
            }
            return result;
        }

        private List<List<String>> commands() {
            return this.commands;
        }
    }

    private record CapturingRunner(List<GitCommandResult> results,
                                   List<List<String>> commands,
                                   List<GitCommandExecutionPolicy> policies) implements GitCommandRunner {

        CapturingRunner(final int exitCode) {
            this(new GitCommandResult(exitCode, ""), new ArrayList<>(), new ArrayList<>());
        }

        CapturingRunner(final int exitCode, final String stdout) {
            this(new GitCommandResult(exitCode, stdout), new ArrayList<>(), new ArrayList<>());
        }

        CapturingRunner(final GitCommandResult... results) {
            this(List.of(results), new ArrayList<>(), new ArrayList<>());
        }

        private CapturingRunner(final GitCommandResult result,
                                final List<List<String>> commands,
                                final List<GitCommandExecutionPolicy> policies) {
            this(List.of(result), commands, policies);
        }

        @Override
        public GitCommandResult run(final List<String> command, final GitCommandExecutionPolicy policy) {
            this.commands.add(List.copyOf(command));
            this.policies.add(policy);
            return this.results.get(Math.min(this.commands.size() - 1, this.results.size() - 1));
        }
    }
}
