package com.sitionix.forgeagent.infrastructure.git;

import com.sitionix.forgeagent.domain.port.GitExecutionException;
import com.sitionix.forgeagent.infrastructure.local.runtime.ManagedRuntimeProcess;
import com.sitionix.forgeagent.infrastructure.local.runtime.RuntimeProcessLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

@Component
final class DefaultGitCommandRunner implements GitCommandRunner {
    private final RuntimeProcessLauncher launcher;

    @Autowired
    DefaultGitCommandRunner(RuntimeProcessLauncher launcher) { this.launcher = launcher; }


    private static final int MAX_CAPTURED_OUTPUT_BYTES = 1024 * 1024;
    @Override
    public GitCommandResult run(final List<String> command, final GitCommandExecutionPolicy policy) {
        final Duration timeout = policy.timeout();
        final long deadline = System.nanoTime() + timeout.toNanos();
        ManagedRuntimeProcess process = null;
        Future<String> stdout = null;
        Future<String> stderr = null;
        final var streamReaders = Executors.newFixedThreadPool(2);
        try {
            process = this.launcher.startGit(command);
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
        } catch (final IOException | IllegalStateException exception) {
            throw new GitExecutionException("Git command failed to start.", exception);
        } catch (final TimeoutException exception) {
            final boolean interrupted = this.cleanupFailedProcess(process, stdout, stderr);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new GitExecutionException("Git command timed out.", exception);
        } catch (final InterruptedException exception) {
            this.cleanupFailedProcess(process, stdout, stderr);
            Thread.currentThread().interrupt();
            throw new GitExecutionException("Git command was interrupted.", exception);
        } catch (final GitExecutionException exception) {
            final boolean interrupted = this.cleanupFailedProcess(process, stdout, stderr);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            throw exception;
        } finally {
            try {
                // Success does not prove hook descendants have exited their owned unit.
                if (process != null) process.terminateOwnedUnit();
            } finally {
                streamReaders.shutdownNow();
            }
        }
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

    private boolean cleanupFailedProcess(final ManagedRuntimeProcess process,
                                         final Future<String> stdout,
                                         final Future<String> stderr) {
        if (process != null) {
            process.terminateOwnedUnit();
            this.closeProcessStreams(process);
        }
        this.cancel(stdout);
        this.cancel(stderr);
        return Thread.currentThread().isInterrupted();
    }
}
