package com.sitionix.forgeagent.infrastructure.git;

import com.sitionix.forgeagent.domain.port.GitExecutionException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

@Component
final class DefaultGitCommandRunner implements GitCommandRunner {

    private static final Duration TERMINATION_WAIT_INTERVAL = Duration.ofMillis(20);
    private static final Duration SIGNAL_COMMAND_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration GRACEFUL_TERMINATION_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration FORCED_TERMINATION_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_CAPTURED_OUTPUT_BYTES = 1024 * 1024;
    private static final String PYTHON_SESSION_LAUNCHER = """
            import os
            import sys
            os.setsid()
            os.execvp(sys.argv[1], sys.argv[1:])
            """;

    @Override
    public GitCommandResult run(final List<String> command, final GitCommandExecutionPolicy policy) {
        final Duration timeout = policy.timeout();
        final long deadline = System.nanoTime() + timeout.toNanos();
        Process process = null;
        Future<String> stdout = null;
        Future<String> stderr = null;
        long processGroupId = -1L;
        final var streamReaders = Executors.newFixedThreadPool(2);
        try {
            final ProcessBuilder builder = new ProcessBuilder(this.sessionCommand(command));
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            process = builder.start();
            processGroupId = process.pid();
            final Process startedProcess = process;
            stdout = streamReaders.submit(() -> this.readLimited(startedProcess.getInputStream()));
            stderr = streamReaders.submit(() -> this.readLimited(startedProcess.getErrorStream()));
            final boolean completed = process.waitFor(this.remainingNanos(deadline), TimeUnit.NANOSECONDS);
            if (!completed) {
                throw new GitExecutionException("Git command timed out.");
            }
            return new GitCommandResult(
                    process.exitValue(),
                    this.awaitStreamReader(stdout, deadline),
                    this.awaitStreamReader(stderr, deadline)
            );
        } catch (final IOException exception) {
            throw new GitExecutionException("Git command failed to start.", exception);
        } catch (final TimeoutException exception) {
            final boolean interrupted = this.cleanupFailedProcess(process, processGroupId, stdout, stderr);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new GitExecutionException("Git command timed out.", exception);
        } catch (final InterruptedException exception) {
            this.cleanupFailedProcess(process, processGroupId, stdout, stderr);
            Thread.currentThread().interrupt();
            throw new GitExecutionException("Git command was interrupted.", exception);
        } catch (final GitExecutionException exception) {
            final boolean interrupted = this.cleanupFailedProcess(process, processGroupId, stdout, stderr);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            throw exception;
        } finally {
            streamReaders.shutdownNow();
        }
    }

    private List<String> sessionCommand(final List<String> command) {
        final List<String> copiedCommand = List.copyOf(command);
        if (copiedCommand.isEmpty()) {
            throw new GitExecutionException("Git command is empty.");
        }
        if (!this.isExecutableCommand(copiedCommand.getFirst())) {
            throw new GitExecutionException("Git command failed to start.");
        }
        final String python = this.findExecutable("python3", "/usr/bin/python3", "/opt/homebrew/bin/python3");
        if (python != null) {
            final List<String> sessionCommand = new ArrayList<>(copiedCommand.size() + 3);
            sessionCommand.add(python);
            sessionCommand.add("-c");
            sessionCommand.add(PYTHON_SESSION_LAUNCHER);
            sessionCommand.addAll(copiedCommand);
            return sessionCommand;
        }
        final String perl = this.findExecutable("perl", "/usr/bin/perl");
        if (perl != null) {
            final List<String> sessionCommand = new ArrayList<>(copiedCommand.size() + 3);
            sessionCommand.add(perl);
            sessionCommand.add("-MPOSIX=setsid");
            sessionCommand.add("-e");
            sessionCommand.add("setsid() or die 'setsid failed'; exec @ARGV; die 'exec failed';");
            sessionCommand.addAll(copiedCommand);
            return sessionCommand;
        }
        throw new GitExecutionException("Git command session launcher is unavailable.");
    }

    private String readLimited(final InputStream stream) throws IOException {
        final byte[] buffer = new byte[8192];
        final byte[] captured = new byte[MAX_CAPTURED_OUTPUT_BYTES];
        int capturedBytes = 0;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            final int remaining = MAX_CAPTURED_OUTPUT_BYTES - capturedBytes;
            if (remaining > 0) {
                final int bytesToCopy = Math.min(remaining, read);
                System.arraycopy(buffer, 0, captured, capturedBytes, bytesToCopy);
                capturedBytes += bytesToCopy;
            }
        }
        return new String(captured, 0, capturedBytes, StandardCharsets.UTF_8);
    }

    private String awaitStreamReader(final Future<String> reader, final long deadline) throws InterruptedException, TimeoutException {
        if (reader == null) {
            return "";
        }
        try {
            return reader.get(this.remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (final ExecutionException exception) {
            throw new GitExecutionException("Git command output could not be read.", exception);
        }
    }

    private long remainingNanos(final long deadline) throws TimeoutException {
        final long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new TimeoutException("Git command deadline expired.");
        }
        return remaining;
    }

    private void cancel(final Future<?> future) {
        if (future != null) {
            future.cancel(true);
        }
    }

    private void closeProcessStreams(final Process process) {
        try {
            process.getInputStream().close();
        } catch (final IOException ignored) {
        }
        try {
            process.getErrorStream().close();
        } catch (final IOException ignored) {
        }
    }

    private boolean cleanupFailedProcess(final Process process,
                                         final long processGroupId,
                                         final Future<String> stdout,
                                         final Future<String> stderr) {
        boolean interrupted = false;
        if (processGroupId > 0) {
            interrupted = this.terminateProcessGroup(processGroupId) || interrupted;
        }
        if (process != null) {
            interrupted = this.terminateRootProcess(process) || interrupted;
            this.closeProcessStreams(process);
        }
        this.cancel(stdout);
        this.cancel(stderr);
        return interrupted;
    }

    private boolean terminateProcessGroup(final long processGroupId) {
        boolean interrupted = false;
        interrupted = this.signalProcessGroup("-TERM", processGroupId) || interrupted;
        interrupted = this.waitUntilProcessGroupDead(processGroupId, GRACEFUL_TERMINATION_TIMEOUT) || interrupted;
        ProcessGroupProbe probe = this.isProcessGroupAlive(processGroupId);
        interrupted = probe.interrupted() || interrupted;
        if (!probe.alive()) {
            return interrupted;
        }
        interrupted = this.signalProcessGroup("-KILL", processGroupId) || interrupted;
        interrupted = this.waitUntilProcessGroupDead(processGroupId, FORCED_TERMINATION_TIMEOUT) || interrupted;
        probe = this.isProcessGroupAlive(processGroupId);
        interrupted = probe.interrupted() || interrupted;
        if (probe.alive()) {
            throw new GitExecutionException("Git command process group could not be terminated.");
        }
        return interrupted;
    }

    private boolean terminateRootProcess(final Process process) {
        boolean interrupted = false;
        if (!process.isAlive()) {
            return false;
        }
        process.destroy();
        interrupted = this.waitForProcessExit(process, GRACEFUL_TERMINATION_TIMEOUT) || interrupted;
        if (!process.isAlive()) {
            return interrupted;
        }
        process.destroyForcibly();
        interrupted = this.waitForProcessExit(process, FORCED_TERMINATION_TIMEOUT) || interrupted;
        if (process.isAlive()) {
            throw new GitExecutionException("Git command root process could not be terminated.");
        }
        return interrupted;
    }

    private boolean waitForProcessExit(final Process process, final Duration timeout) {
        try {
            process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return false;
        } catch (final InterruptedException exception) {
            return true;
        }
    }

    private boolean waitUntilProcessGroupDead(final long processGroupId, final Duration timeout) {
        boolean interrupted = false;
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            final ProcessGroupProbe probe = this.isProcessGroupAlive(processGroupId);
            interrupted = probe.interrupted() || interrupted;
            if (!probe.alive()) {
                return interrupted;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(TERMINATION_WAIT_INTERVAL.toMillis());
            } catch (final InterruptedException exception) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private ProcessGroupProbe isProcessGroupAlive(final long processGroupId) {
        final Process process;
        try {
            process = this.killProcess("-0", processGroupId);
        } catch (final IOException exception) {
            throw new GitExecutionException("Git command process group could not be inspected.", exception);
        }
        try {
            final boolean completed = process.waitFor(SIGNAL_COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                return new ProcessGroupProbe(true, false);
            }
            return new ProcessGroupProbe(process.exitValue() == 0, false);
        } catch (final InterruptedException exception) {
            process.destroyForcibly();
            return new ProcessGroupProbe(true, true);
        }
    }

    private boolean signalProcessGroup(final String signal, final long processGroupId) {
        final Process process;
        try {
            process = this.killProcess(signal, processGroupId);
        } catch (final IOException exception) {
            throw new GitExecutionException("Git command process group could not be terminated.", exception);
        }
        try {
            final boolean completed = process.waitFor(SIGNAL_COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
            }
            return false;
        } catch (final InterruptedException exception) {
            process.destroyForcibly();
            return true;
        }
    }

    private Process killProcess(final String signal, final long processGroupId) throws IOException {
        return new ProcessBuilder(this.killExecutable(), signal, "-" + processGroupId)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private String killExecutable() {
        final String kill = this.findExecutable("kill", "/bin/kill", "/usr/bin/kill");
        if (kill == null) {
            throw new GitExecutionException("Git command process group killer is unavailable.");
        }
        return kill;
    }

    private String findExecutable(final String name, final String... candidates) {
        for (final String candidate : candidates) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return Arrays.stream(System.getenv().getOrDefault("PATH", "").split(":"))
                .filter(path -> !path.isBlank())
                .map(path -> Path.of(path, name))
                .filter(Files::isExecutable)
                .map(Path::toString)
                .findFirst()
                .orElse(null);
    }

    private boolean isExecutableCommand(final String command) {
        final Path commandPath = Path.of(command);
        if (commandPath.getNameCount() > 1 || commandPath.isAbsolute()) {
            return Files.isExecutable(commandPath);
        }
        return this.findExecutable(command) != null;
    }

    private record ProcessGroupProbe(boolean alive, boolean interrupted) {
    }
}
