package com.sitionix.forgeagent.infrastructure.git;

import com.sitionix.forgeagent.domain.port.GitExecutionException;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
final class DefaultGitCommandRunner implements GitCommandRunner {

    private static final Duration TERMINATION_WAIT_INTERVAL = Duration.ofMillis(20);
    private static final Duration GRACEFUL_TERMINATION_TIMEOUT = Duration.ofSeconds(1);

    @Override
    public GitCommandResult run(final List<String> command, final GitCommandExecutionPolicy policy) {
        final Duration timeout = policy.timeout();
        Process process = null;
        try {
            final ProcessBuilder builder = new ProcessBuilder(List.copyOf(command));
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            process = builder.start();
            final boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                final boolean interrupted = this.terminateProcessTree(process);
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                throw new GitExecutionException("Git command timed out.");
            }
            return new GitCommandResult(process.exitValue(), "");
        } catch (final IOException exception) {
            throw new GitExecutionException("Git command failed to start.", exception);
        } catch (final InterruptedException exception) {
            if (process != null) {
                this.terminateProcessTree(process);
            }
            Thread.currentThread().interrupt();
            throw new GitExecutionException("Git command was interrupted.", exception);
        }
    }

    private boolean terminateProcessTree(final Process process) {
        boolean interrupted = false;
        final ProcessHandle root = process.toHandle();
        final List<ProcessHandle> handles = this.collectProcessTree(root);

        for (final ProcessHandle handle : handles) {
            if (handle.isAlive()) {
                handle.destroy();
            }
        }
        interrupted = this.waitUntilDead(handles, GRACEFUL_TERMINATION_TIMEOUT) || interrupted;

        final List<ProcessHandle> remainingHandles = this.merge(handles, this.collectProcessTree(root));
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
