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
    public boolean refExists(final Path repository, final String ref) {
        return this.git(repository, "rev-parse", "--verify", ref).success();
    }

    @Override
    public boolean isAncestor(final Path repository, final String ancestorRef, final String descendantRef) {
        return this.git(repository, "merge-base", "--is-ancestor", ancestorRef, descendantRef).success();
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
        command.add("-C");
        command.add(repository.toString());
        command.addAll(List.of(args));
        try {
            final Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            final boolean exited = process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                throw new IllegalArgumentException("Git command timed out: " + String.join(" ", command));
            }
            final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new CommandResult(process.exitValue() == 0, output, String.join(" ", command));
        } catch (final IOException ex) {
            throw new IllegalArgumentException("Git command failed to start: " + String.join(" ", command), ex);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Git command interrupted: " + String.join(" ", command), ex);
        }
    }

    private record CommandResult(boolean success, String stdout, String command) {
    }
}
