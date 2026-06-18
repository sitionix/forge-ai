package com.sitionix.forgeai.infrastructure.githubcli.adapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GitCliRepositoryAdapterTest {

    @TempDir
    private Path repository;

    private final GitCliRepositoryAdapter adapter = new GitCliRepositoryAdapter();

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(this.command(Path.of("."), "git", "--version").exitCode() == 0);
        this.git("init");
        this.git("config", "user.email", "forge-ai@example.test");
        this.git("config", "user.name", "Forge AI Test");
        this.git("checkout", "-b", "develop");
        Files.writeString(this.repository.resolve("README.md"), "initial\n", StandardCharsets.UTF_8);
        this.git("add", "README.md");
        this.git("commit", "-m", "Initial");
        this.git("checkout", "-b", "feature/SITIONIX-1");
    }

    @Test
    void givenGitRepository_whenInspect_thenReturnGenericGitState() {
        assertThat(this.adapter.currentBranch(this.repository).trim()).isEqualTo("feature/SITIONIX-1");
        assertThat(this.adapter.headCommit(this.repository).trim()).matches("[0-9a-f]{40}");
        assertThat(this.adapter.statusPorcelain(this.repository).trim()).isEmpty();
        assertThat(this.adapter.refExists(this.repository, "develop^{commit}")).isTrue();
        assertThat(this.adapter.isAncestor(this.repository, "develop", "HEAD")).isTrue();
    }

    @Test
    void givenLargeStatusOutput_whenStatusPorcelain_thenDrainOutputWithoutTimeout() throws Exception {
        for (int index = 0; index < 3_000; index++) {
            Files.writeString(
                    this.repository.resolve("untracked-" + index + ".txt"),
                    "dirty\n",
                    StandardCharsets.UTF_8
            );
        }

        final String status = this.adapter.statusPorcelain(this.repository);

        assertThat(status)
                .contains("?? untracked-0.txt")
                .contains("?? untracked-2999.txt");
    }

    private CommandResult git(final String... args) {
        final List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(this.repository.toString());
        command.addAll(List.of(args));
        return this.command(this.repository, command.toArray(String[]::new));
    }

    private CommandResult command(final Path directory, final String... args) {
        try {
            final Process process = new ProcessBuilder(args)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            final boolean exited = process.waitFor(10, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                throw new AssertionError("Command timed out: " + String.join(" ", args));
            }
            final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new AssertionError("Command failed: " + String.join(" ", args) + "\n" + output);
            }
            return new CommandResult(process.exitValue(), output);
        } catch (final IOException ex) {
            return new CommandResult(1, ex.getMessage());
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Command interrupted: " + String.join(" ", args), ex);
        }
    }

    private record CommandResult(int exitCode, String stdout) {
    }
}
