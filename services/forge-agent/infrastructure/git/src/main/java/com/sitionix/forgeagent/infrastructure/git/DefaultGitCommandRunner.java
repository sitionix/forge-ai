package com.sitionix.forgeagent.infrastructure.git;

import com.sitionix.forgeagent.domain.port.GitOperationException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
final class DefaultGitCommandRunner implements GitCommandRunner {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Override
    public GitCommandResult run(final List<String> command) {
        try {
            final ProcessBuilder builder = new ProcessBuilder(List.copyOf(command));
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            final Process process = builder.start();
            final boolean completed = process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new GitOperationException("Git command timed out.");
            }
            return new GitCommandResult(process.exitValue(), "");
        } catch (final IOException exception) {
            throw new GitOperationException("Git command failed to start.", exception);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitOperationException("Git command was interrupted.", exception);
        }
    }
}
