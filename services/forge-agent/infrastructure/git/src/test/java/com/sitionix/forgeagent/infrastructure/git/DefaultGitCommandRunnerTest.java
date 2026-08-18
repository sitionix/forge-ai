package com.sitionix.forgeagent.infrastructure.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeagent.domain.port.GitExecutionException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultGitCommandRunnerTest {

    @TempDir
    private Path tempDir;

    @Test
    void timeoutTerminatesStartedProcessAndDescendantBeforeThrowing() throws Exception {
        final Path parentPidFile = this.tempDir.resolve("parent.pid");
        final Path childPidFile = this.tempDir.resolve("child.pid");
        final Path script = this.writeProcessTreeScript(parentPidFile, childPidFile);
        final DefaultGitCommandRunner runner = new DefaultGitCommandRunner();

        assertThatThrownBy(() -> runner.run(List.of("/bin/sh", script.toString()),
                new GitCommandExecutionPolicy(Duration.ofSeconds(1))))
                .isInstanceOf(GitExecutionException.class)
                .hasMessage("Git command timed out.");

        assertThatProcessIsDead(this.readPid(parentPidFile));
        assertThatProcessIsDead(this.readPid(childPidFile));
    }

    @Test
    void capturesStdoutCorrectly() {
        final DefaultGitCommandRunner runner = new DefaultGitCommandRunner();

        final GitCommandResult result = runner.run(List.of("/bin/sh", "-c", "printf 'hello\\nworld\\n'"),
                new GitCommandExecutionPolicy(Duration.ofSeconds(5)));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("hello\nworld\n");
    }

    @Test
    void largeStdoutDoesNotDeadlock() {
        final DefaultGitCommandRunner runner = new DefaultGitCommandRunner();

        final GitCommandResult result = runner.run(List.of("/bin/sh", "-c", "yes output | head -n 200000"),
                new GitCommandExecutionPolicy(Duration.ofSeconds(5)));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).startsWith("output\n");
        assertThat(result.stdout().length()).isGreaterThan(100_000);
    }

    @Test
    void timeoutStillAppliesWhileOutputIsBeingProduced() {
        final DefaultGitCommandRunner runner = new DefaultGitCommandRunner();

        assertThatThrownBy(() -> runner.run(List.of("/bin/sh", "-c", "while true; do printf 'output\\n'; done"),
                new GitCommandExecutionPolicy(Duration.ofMillis(200))))
                .isInstanceOf(GitExecutionException.class)
                .hasMessage("Git command timed out.");
    }

    @Test
    void timeoutCoversInheritedOutputPipesAfterRootProcessExits() throws Exception {
        final Path childPidFile = this.tempDir.resolve("inherited-pipe-child.pid");
        final Path script = this.writeInheritedPipeScript(childPidFile);
        final DefaultGitCommandRunner runner = new DefaultGitCommandRunner();
        final Instant startedAt = Instant.now();

        assertThatThrownBy(() -> runner.run(List.of("/bin/sh", script.toString()),
                new GitCommandExecutionPolicy(Duration.ofMillis(300))))
                .isInstanceOf(GitExecutionException.class)
                .hasMessage("Git command timed out.");

        assertThat(Duration.between(startedAt, Instant.now())).isLessThan(Duration.ofSeconds(5));
        assertThatProcessIsDead(this.readPid(childPidFile));
    }

    @Test
    void interruptionTerminatesStartedProcessAndDescendantBeforeThrowingAndPreservesInterruptStatus() throws Exception {
        final Path parentPidFile = this.tempDir.resolve("parent.pid");
        final Path childPidFile = this.tempDir.resolve("child.pid");
        final Path script = this.writeProcessTreeScript(parentPidFile, childPidFile);
        final DefaultGitCommandRunner runner = new DefaultGitCommandRunner();
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final AtomicBoolean interruptedStatus = new AtomicBoolean();
        final Thread runnerThread = new Thread(() -> {
            try {
                runner.run(List.of("/bin/sh", script.toString()), new GitCommandExecutionPolicy(Duration.ofMinutes(5)));
            } catch (final Throwable exception) {
                thrown.set(exception);
                interruptedStatus.set(Thread.currentThread().isInterrupted());
            }
        });

        runnerThread.start();
        this.waitUntilExists(childPidFile);
        runnerThread.interrupt();
        runnerThread.join(Duration.ofSeconds(5).toMillis());

        assertThat(runnerThread.isAlive()).isFalse();
        assertThat(thrown.get())
                .isInstanceOf(GitExecutionException.class)
                .hasMessage("Git command was interrupted.");
        assertThat(interruptedStatus).isTrue();
        assertThatProcessIsDead(this.readPid(parentPidFile));
        assertThatProcessIsDead(this.readPid(childPidFile));
    }

    private Path writeProcessTreeScript(final Path parentPidFile, final Path childPidFile) throws Exception {
        final Path script = this.tempDir.resolve("process-tree.sh");
        Files.writeString(script, """
                echo $$ > '%s'
                sleep 60 &
                echo $! > '%s'
                wait $!
                """.formatted(parentPidFile, childPidFile));
        return script;
    }

    private Path writeInheritedPipeScript(final Path childPidFile) throws Exception {
        final Path script = this.tempDir.resolve("inherited-pipe.sh");
        Files.writeString(script, """
                sleep 60 &
                echo $! > '%s'
                exit 0
                """.formatted(childPidFile));
        return script;
    }

    private long readPid(final Path pidFile) throws Exception {
        this.waitUntilExists(pidFile);
        return Long.parseLong(Files.readString(pidFile).trim());
    }

    private void waitUntilExists(final Path path) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Files.exists(path) && System.nanoTime() < deadline) {
            Thread.sleep(Duration.ofMillis(20).toMillis());
        }
        assertThat(path).exists();
    }

    private static void assertThatProcessIsDead(final long pid) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) {
            Thread.sleep(Duration.ofMillis(20).toMillis());
        }
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }
}
