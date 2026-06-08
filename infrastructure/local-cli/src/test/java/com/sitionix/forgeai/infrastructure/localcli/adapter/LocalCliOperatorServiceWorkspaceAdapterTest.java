package com.sitionix.forgeai.infrastructure.localcli.adapter;

import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceDefaultMode;
import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceWorkspaceState;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class LocalCliOperatorServiceWorkspaceAdapterTest {

    private final LocalCliOperatorServiceWorkspaceAdapter adapter = new LocalCliOperatorServiceWorkspaceAdapter();

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
}
