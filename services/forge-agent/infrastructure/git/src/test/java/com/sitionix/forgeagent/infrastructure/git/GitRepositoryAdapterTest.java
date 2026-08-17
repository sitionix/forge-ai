package com.sitionix.forgeagent.infrastructure.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeagent.domain.port.GitOperationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(command -> new GitCommandResult(0, ""));

        assertThat(adapter.resolveRepositoryName("git@gitlab.com:company/siteservice-sox.git")).isEqualTo("siteservice-sox");
        assertThat(adapter.resolveRepositoryName("https://github.com/company/backend.git")).isEqualTo("backend");
        assertThat(adapter.resolveRepositoryName("ssh://example.com/company/authservice-sox")).isEqualTo("authservice-sox");
    }

    @Test
    void unreachableRemoteFailsInspection() {
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(command -> new GitCommandResult(128, "fatal"));

        assertThatThrownBy(() -> adapter.inspectRemote("git@example.com:missing.git"))
                .isInstanceOf(GitOperationException.class)
                .hasMessage("Git remote is not reachable.");
    }

    @Test
    void cloneUsesArgumentBasedGitCommand() {
        final CapturingRunner runner = new CapturingRunner(0);
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(runner);
        final Path target = this.tempDir.resolve("target");

        adapter.clone("https://example.com/company/service.git", target);

        assertThat(runner.commands()).containsExactly(List.of("git", "clone", "https://example.com/company/service.git", target.toString()));
    }

    @Test
    void remoteUrlIsSingleProcessArgument() {
        final CapturingRunner runner = new CapturingRunner(0);
        final GitRepositoryAdapter adapter = new GitRepositoryAdapter(runner);
        final String remoteUrl = "https://example.com/company/service.git;rm -rf /";

        adapter.inspectRemote(remoteUrl);

        assertThat(runner.commands()).containsExactly(List.of("git", "ls-remote", remoteUrl));
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

    private record CapturingRunner(int exitCode, List<List<String>> commands) implements GitCommandRunner {

        CapturingRunner(final int exitCode) {
            this(exitCode, new ArrayList<>());
        }

        @Override
        public GitCommandResult run(final List<String> command) {
            this.commands.add(List.copyOf(command));
            return new GitCommandResult(this.exitCode, "");
        }
    }
}
