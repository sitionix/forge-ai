package com.sitionix.forgeai.infrastructure.localcli.adapter;

import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceDefaultMode;
import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceWorkspaceState;
import com.sitionix.forgeai.domain.port.GitRepositoryPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class LocalCliOperatorServiceWorkspaceAdapterTest {

    private final LocalCliOperatorServiceWorkspaceAdapter adapter = new LocalCliOperatorServiceWorkspaceAdapter(new TestGitRepositoryPort());

    @TempDir
    private Path tempDir;

    @Test
    void givenMissingAbsolutePath_whenInspect_thenReturnCloneAvailability() {
        final Path missing = this.tempDir.resolve("missing-service");

        final OperatorServiceWorkspaceState actual = this.adapter.inspect(
                "service",
                missing.toString(),
                "Sitionix/missing-service"
        );

        assertThat(actual.exists()).isFalse();
        assertThat(actual.gitRepository()).isFalse();
        assertThat(actual.absolutePath()).isEqualTo(missing.toString());
        assertThat(actual.cloneUrl()).isEqualTo("git@github.com:Sitionix/missing-service.git");
    }

    @Test
    void givenLocalGitRepository_whenInspect_thenReturnBranchAndDirtyState() throws Exception {
        final Path repo = this.dirtyFeatureRepository("feature/SITIONIX-28");

        final OperatorServiceWorkspaceState actual = this.adapter.inspect(
                "service",
                repo.toString(),
                "Sitionix/service"
        );

        assertThat(actual.exists()).isTrue();
        assertThat(actual.gitRepository()).isTrue();
        assertThat(actual.branch()).isEqualTo("feature/SITIONIX-28");
        assertThat(actual.defaultBranch()).isEqualTo("develop");
        assertThat(actual.dirty()).isTrue();
    }

    @Test
    void givenGitStatusFailure_whenInspect_thenReturnDegradedWorkspaceState() throws Exception {
        final Path repo = this.dirtyFeatureRepository("feature/SITIONIX-30");
        final LocalCliOperatorServiceWorkspaceAdapter failingAdapter = new LocalCliOperatorServiceWorkspaceAdapter(
                new FailingStatusGitRepositoryPort()
        );

        final OperatorServiceWorkspaceState actual = failingAdapter.inspect(
                "service",
                repo.toString(),
                "Sitionix/service"
        );

        assertThat(actual.exists()).isTrue();
        assertThat(actual.gitRepository()).isTrue();
        assertThat(actual.branch()).isEqualTo("feature/SITIONIX-30");
        assertThat(actual.defaultBranch()).isEqualTo("develop");
        assertThat(actual.dirty()).isTrue();
        assertThat(actual.warnings())
                .anySatisfy(warning -> assertThat(warning)
                        .contains("Git workspace inspection failed")
                        .contains("status timed out"));
    }

    @Test
    void givenDirtyRepository_whenDefaultWithStash_thenCheckoutDefaultBranchAndKeepFeatureBranch() throws Exception {
        final Path repo = this.dirtyFeatureRepository("feature/SITIONIX-28");

        final OperatorServiceWorkspaceState actual = this.adapter.resetToDefaultBranch(
                "service",
                repo.toString(),
                "Sitionix/service",
                OperatorServiceDefaultMode.STASH
        );

        assertThat(actual.branch()).isEqualTo("develop");
        assertThat(actual.dirty()).isFalse();
        assertThat(this.output("git", "-C", repo.toString(), "branch", "--list", "feature/SITIONIX-28"))
                .contains("feature/SITIONIX-28");
        assertThat(this.output("git", "-C", repo.toString(), "stash", "list"))
                .contains("forge-ai default SITIONIX-28");
    }

    @Test
    void givenDirtyRepository_whenDefaultWithCommit_thenCommitWithTicketKeyAndKeepFeatureBranch() throws Exception {
        final Path repo = this.dirtyFeatureRepository("feature/SITIONIX-29");

        final OperatorServiceWorkspaceState actual = this.adapter.resetToDefaultBranch(
                "service",
                repo.toString(),
                "Sitionix/service",
                OperatorServiceDefaultMode.COMMIT
        );

        assertThat(actual.branch()).isEqualTo("develop");
        assertThat(actual.dirty()).isFalse();
        assertThat(this.output("git", "-C", repo.toString(), "branch", "--list", "feature/SITIONIX-29"))
                .contains("feature/SITIONIX-29");
        assertThat(this.output("git", "-C", repo.toString(), "log", "--format=%s", "-1", "feature/SITIONIX-29"))
                .isEqualTo("[SITIONIX-29] - default local service workspace");
    }

    private Path dirtyFeatureRepository(final String branchName) throws Exception {
        final Path repo = this.tempDir.resolve(branchName.replace('/', '-'));
        Files.createDirectories(repo);
        this.run("git", "init", "-b", "develop", repo.toString());
        Files.writeString(repo.resolve("README.md"), "initial\n", StandardCharsets.UTF_8);
        this.run("git", "-C", repo.toString(), "add", "README.md");
        this.run("git", "-C", repo.toString(), "-c", "user.name=Forge", "-c", "user.email=forge@example.com", "commit", "-m", "init");
        this.run("git", "-C", repo.toString(), "checkout", "-b", branchName);
        Files.writeString(repo.resolve("dirty.txt"), "dirty\n", StandardCharsets.UTF_8);
        return repo;
    }

    private void run(final String... command) throws Exception {
        final Process process = new ProcessBuilder(command).start();
        final int exitCode = process.waitFor();
        assertThat(exitCode)
                .as(String.join(" ", command))
                .isZero();
    }

    private String output(final String... command) throws Exception {
        final Process process = new ProcessBuilder(command).start();
        final String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        final int exitCode = process.waitFor();
        assertThat(exitCode)
                .as(String.join(" ", command))
                .isZero();
        return stdout.trim();
    }

    private class TestGitRepositoryPort implements GitRepositoryPort {

        @Override
        public boolean isInsideWorkTree(final Path repository) {
            return this.git(repository, "rev-parse", "--is-inside-work-tree").exitCode() == 0;
        }

        @Override
        public String currentBranch(final Path repository) {
            return this.requireGit(repository, "branch", "--show-current");
        }

        @Override
        public String headCommit(final Path repository) {
            return this.requireGit(repository, "rev-parse", "HEAD");
        }

        @Override
        public String statusPorcelain(final Path repository) {
            return this.requireGit(repository, "status", "--porcelain=v1");
        }

        @Override
        public String defaultBranch(final Path repository, final List<String> branchCandidates) {
            final CommandResult originHead = this.git(repository, "symbolic-ref", "--short", "refs/remotes/origin/HEAD");
            final String originHeadText = originHead.stdout().trim();
            if (originHead.exitCode() == 0 && originHeadText.startsWith("origin/")) {
                return originHeadText.substring("origin/".length());
            }
            for (String candidate : branchCandidates) {
                if (this.refExists(repository, "origin/" + candidate) || this.refExists(repository, candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        @Override
        public boolean refExists(final Path repository, final String ref) {
            return this.git(repository, "rev-parse", "--verify", ref).exitCode() == 0;
        }

        @Override
        public boolean isAncestor(final Path repository, final String ancestorRef, final String descendantRef) {
            return this.git(repository, "merge-base", "--is-ancestor", ancestorRef, descendantRef).exitCode() == 0;
        }

        @Override
        public void clone(final String cloneUrl, final Path targetDirectory) {
            this.requireCommand("git", "clone", cloneUrl, targetDirectory.toString());
        }

        @Override
        public void addAll(final Path repository) {
            this.requireGit(repository, "add", "-A");
        }

        @Override
        public void commit(final Path repository, final String userName, final String userEmail, final String message) {
            this.requireGit(repository, "-c", "user.name=" + userName, "-c", "user.email=" + userEmail, "commit", "-m", message);
        }

        @Override
        public void stash(final Path repository, final String message) {
            this.requireGit(repository, "stash", "push", "-u", "-m", message);
        }

        @Override
        public void fetch(final Path repository, final String remote, final String branch) {
            this.requireGit(repository, "fetch", remote, branch);
        }

        @Override
        public void checkout(final Path repository, final String branch) {
            this.requireGit(repository, "checkout", branch);
        }

        @Override
        public void pullFastForwardOnly(final Path repository, final String remote, final String branch) {
            this.requireGit(repository, "pull", "--ff-only", remote, branch);
        }

        private String requireGit(final Path repository, final String... args) {
            final CommandResult result = this.git(repository, args);
            if (result.exitCode() != 0) {
                throw new IllegalArgumentException(result.stderr());
            }
            return result.stdout();
        }

        private CommandResult git(final Path repository, final String... args) {
            final List<String> command = new java.util.ArrayList<>();
            command.add("git");
            command.add("-C");
            command.add(repository.toString());
            command.addAll(List.of(args));
            return this.command(command.toArray(String[]::new));
        }

        private void requireCommand(final String... command) {
            final CommandResult result = this.command(command);
            if (result.exitCode() != 0) {
                throw new IllegalArgumentException(result.stderr());
            }
        }

        private CommandResult command(final String... command) {
            try {
                final Process process = new ProcessBuilder(command).start();
                final String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                final String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                final int exitCode = process.waitFor();
                return new CommandResult(exitCode, stdout, stderr);
            } catch (Exception exception) {
                return new CommandResult(-1, "", exception.getMessage());
            }
        }
    }

    private final class FailingStatusGitRepositoryPort extends TestGitRepositoryPort {

        @Override
        public String statusPorcelain(final Path repository) {
            throw new IllegalArgumentException("status timed out");
        }
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
