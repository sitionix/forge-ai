package com.sitionix.forgeagent.infrastructure.git;

import com.sitionix.forgeagent.domain.port.GitExecutionException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

@Component
final class DefaultGitCommandRunner implements GitCommandRunner {

    private static final Duration PROCESS_TRACK_INTERVAL = Duration.ofMillis(2);
    private static final Duration TERMINATION_WAIT_INTERVAL = Duration.ofMillis(20);
    private static final Duration GRACEFUL_TERMINATION_TIMEOUT = Duration.ofSeconds(1);
    private static final int MAX_CAPTURED_OUTPUT_BYTES = 1024 * 1024;

    @Override
    public GitCommandResult run(final List<String> command, final GitCommandExecutionPolicy policy) {
        final Duration timeout = policy.timeout();
        final long deadline = System.nanoTime() + timeout.toNanos();
        Process process = null;
        Future<String> stdout = null;
        Future<String> stderr = null;
        Future<?> processTracker = null;
        final Set<ProcessHandle> trackedProcesses = ConcurrentHashMap.newKeySet();
        final var streamReaders = Executors.newFixedThreadPool(2);
        final var processTrackers = Executors.newSingleThreadExecutor();
        try {
            final ProcessBuilder builder = new ProcessBuilder(List.copyOf(command));
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            process = builder.start();
            final Process startedProcess = process;
            processTracker = processTrackers.submit(() -> this.trackProcessTree(startedProcess.toHandle(), trackedProcesses));
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
            final boolean interrupted = this.cleanupFailedProcess(process, trackedProcesses, stdout, stderr);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new GitExecutionException("Git command timed out.", exception);
        } catch (final InterruptedException exception) {
            this.cleanupFailedProcess(process, trackedProcesses, stdout, stderr);
            Thread.currentThread().interrupt();
            throw new GitExecutionException("Git command was interrupted.", exception);
        } catch (final GitExecutionException exception) {
            final boolean interrupted = this.cleanupFailedProcess(process, trackedProcesses, stdout, stderr);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            throw exception;
        } finally {
            this.cancel(processTracker);
            processTrackers.shutdownNow();
            streamReaders.shutdownNow();
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

    private boolean cleanupFailedProcess(final Process process,
                                         final Set<ProcessHandle> trackedProcesses,
                                         final Future<String> stdout,
                                         final Future<String> stderr) {
        boolean interrupted = false;
        if (process != null) {
            interrupted = this.terminateProcessTree(process, trackedProcesses) || interrupted;
            this.closeProcessStreams(process);
        }
        this.cancel(stdout);
        this.cancel(stderr);
        return interrupted;
    }

    private void trackProcessTree(final ProcessHandle root, final Set<ProcessHandle> trackedProcesses) {
        trackedProcesses.add(root);
        while (root.isAlive()) {
            trackedProcesses.add(root);
            root.descendants().forEach(trackedProcesses::add);
            try {
                TimeUnit.MILLISECONDS.sleep(PROCESS_TRACK_INTERVAL.toMillis());
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        trackedProcesses.add(root);
        root.descendants().forEach(trackedProcesses::add);
    }

    private boolean terminateProcessTree(final Process process, final Set<ProcessHandle> trackedProcesses) {
        boolean interrupted = false;
        final ProcessHandle root = process.toHandle();
        final List<ProcessHandle> handles = this.merge(new ArrayList<>(trackedProcesses), this.collectProcessTree(root));

        for (final ProcessHandle handle : handles) {
            if (handle.isAlive()) {
                handle.destroy();
            }
        }
        interrupted = this.waitUntilDead(handles, GRACEFUL_TERMINATION_TIMEOUT) || interrupted;

        final List<ProcessHandle> remainingHandles = this.merge(
                this.merge(handles, new ArrayList<>(trackedProcesses)),
                this.collectProcessTree(root)
        );
        for (final ProcessHandle handle : remainingHandles) {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        }
        interrupted = this.waitUntilDead(remainingHandles, null) || interrupted;
        return interrupted;
    }

    private List<ProcessHandle> collectProcessTree(final ProcessHandle root) {
        final List<ProcessHandle> handles = new ArrayList<>(root.descendants()
                .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .toList());
        handles.add(root);
        return handles;
    }

    private List<ProcessHandle> merge(final List<ProcessHandle> first, final List<ProcessHandle> second) {
        final LinkedHashSet<ProcessHandle> handles = new LinkedHashSet<>(first);
        handles.addAll(second);
        return new ArrayList<>(handles);
    }

    private boolean waitUntilDead(final List<ProcessHandle> handles, final Duration timeout) {
        boolean interrupted = false;
        final long deadline = timeout == null ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
        while (handles.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            try {
                TimeUnit.MILLISECONDS.sleep(TERMINATION_WAIT_INTERVAL.toMillis());
            } catch (final InterruptedException exception) {
                interrupted = true;
            }
        }
        return interrupted;
    }
}
