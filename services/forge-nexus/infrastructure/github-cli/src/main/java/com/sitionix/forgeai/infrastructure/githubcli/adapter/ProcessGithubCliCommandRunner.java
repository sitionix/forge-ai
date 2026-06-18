package com.sitionix.forgeai.infrastructure.githubcli.adapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
class ProcessGithubCliCommandRunner implements GithubCliCommandRunner {

    @Override
    public GithubCliCommandResult run(final List<String> command, final Duration timeout) {
        try {
            final Process process = new ProcessBuilder(command).start();
            final boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                return new GithubCliCommandResult(false, "", "Command timed out");
            }
            return new GithubCliCommandResult(
                    process.exitValue() == 0,
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim(),
                    new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim()
            );
        } catch (final IOException exception) {
            return new GithubCliCommandResult(false, "", exception.getMessage());
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new GithubCliCommandResult(false, "", exception.getMessage());
        }
    }
}
