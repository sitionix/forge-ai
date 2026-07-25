package com.sitionix.forgeai.infrastructure.githubcli.adapter;

import com.sitionix.forgeai.domain.port.GitRepositoryPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class GitCliRepositoryAdapter implements GitRepositoryPort {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(10);

    @Override
    public boolean isInsideWorkTree(final Path repository) {
        return this.git(repository, "rev-parse", "--is-inside-work-tree").success();
    }

    @Override
    public String currentBranch(final Path repository) {
        return this.requireSuccess(repository, "branch", "--show-current").stdout();
    }

    @Override
    public String headCommit(final Path repository) {
        return this.requireSuccess(repository, "rev-parse", "HEAD").stdout();
    }

    @Override
    public String statusPorcelain(final Path repository) {
        return this.requireSuccess(repository, "status", "--porcelain=v1").stdout();
    }

    @Override
    public String defaultBranch(final Path repository, final List<String> branchCandidates) {
        final String originHead = this.text(this.git(repository, "symbolic-ref", "--short", "refs/remotes/origin/HEAD").stdout());
        if (originHead != null && originHead.startsWith("origin/")) {
            return originHead.substring("origin/".length());
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
        return this.git(repository, "rev-parse", "--verify", ref).success();
    }

    @Override
    public boolean isAncestor(final Path repository, final String ancestorRef, final String descendantRef) {
        return this.git(repository, "merge-base", "--is-ancestor", ancestorRef, descendantRef).success();
    }

    @Override
    public void clone(final String cloneUrl, final Path targetDirectory) {
        this.requireSuccess(null, "clone", cloneUrl, targetDirectory.toString());
    }

    @Override
    public void addAll(final Path repository) {
        this.requireSuccess(repository, "add", "-A");
    }

    @Override
    public void commit(final Path repository, final String userName, final String userEmail, final String message) {
        this.requireSuccess(
                repository,
                "-c",
                "user.name=" + userName,
                "-c",
                "user.email=" + userEmail,
                "commit",
                "-m",
                message
        );
    }

    @Override
    public void stash(final Path repository, final String message) {
        this.requireSuccess(repository, "stash", "push", "-u", "-m", message);
    }

    @Override
    public void fetch(final Path repository, final String remote, final String branch) {
        this.requireSuccess(repository, "fetch", remote, branch);
    }

    @Override
    public void checkout(final Path repository, final String branch) {
        this.requireSuccess(repository, "checkout", branch);
    }

    @Override
    public void pullFastForwardOnly(final Path repository, final String remote, final String branch) {
        this.requireSuccess(repository, "pull", "--ff-only", remote, branch);
    }

    private CommandResult requireSuccess(final Path repository, final String... args) {
        final CommandResult result = this.git(repository, args);
        if (!result.success()) {
            throw new IllegalArgumentException("Git command failed: " + result.command() + "\n" + result.stdout());
        }
        return result;
    }

    private CommandResult git(final Path repository, final String... args) {
        final List<String> command = new ArrayList<>();
        command.add("git");
        if (repository != null) {
            command.add("-C");
            command.add(repository.toString());
        }
        command.addAll(List.of(args));
        try {
            final Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            final StringBuilder output = new StringBuilder();
            final Thread outputReader = new Thread(
                    () -> this.readOutput(process, output),
                    "forge-ai-git-output-reader"
            );
            outputReader.setDaemon(true);
            outputReader.start();
            final boolean exited = process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                throw new IllegalArgumentException("Git command timed out: " + String.join(" ", command));
            }
            outputReader.join(TimeUnit.SECONDS.toMillis(1));
            return new CommandResult(process.exitValue() == 0, output.toString(), String.join(" ", command));
        } catch (final IOException ex) {
            throw new IllegalArgumentException("Git command failed to start: " + String.join(" ", command), ex);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Git command interrupted: " + String.join(" ", command), ex);
        }
    }

    private void readOutput(final Process process, final StringBuilder output) {
        try {
            output.append(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (final IOException ignored) {
            // The process timeout path destroys the process; command failure is reported by the caller.
        }
    }

    private String text(final String value) {
        final String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record CommandResult(boolean success, String stdout, String command) {
    }
}
