package com.sitionix.forgeai.infrastructure.codexcli.adapter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TerminalTabLauncher {

    private static final String ITERM_SCRIPT_PATH = "scripts/open-in-iterm.applescript";
    private static final String TERMINAL_SCRIPT_PATH = "scripts/open-in-terminal.applescript";

    public void launch(final String command, final String sourceTerminalTty) {
        if (this.runOsaScript(this.loadScript(ITERM_SCRIPT_PATH), command, sourceTerminalTty, false)) {
            return;
        }
        if (!this.runOsaScript(this.loadScript(TERMINAL_SCRIPT_PATH), command, sourceTerminalTty, true)) {
            throw new IllegalStateException("Failed to open iTerm and Terminal tabs for Codex execution");
        }
    }

    private String loadScript(final String resourcePath) {
        try (InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("AppleScript resource not found: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to read AppleScript resource: " + resourcePath, e);
        }
    }

    private boolean runOsaScript(
            final String script,
            final String command,
            final String sourceTerminalTty,
            final boolean throwOnFailure) {
        final List<String> processCommand = List.of(
                "osascript",
                "-e",
                script,
                command,
                sourceTerminalTty != null ? sourceTerminalTty : ""
        );
        final ProcessBuilder processBuilder = new ProcessBuilder(processCommand);
        processBuilder.redirectErrorStream(true);
        try {
            final Process process = processBuilder.start();
            final int exitCode = process.waitFor();
            if (exitCode != 0) {
                if (throwOnFailure) {
                    throw new IllegalStateException("Failed to run osascript. Output: " + this.readOutput(process));
                }
                return false;
            }
            return true;
        } catch (final IOException e) {
            if (throwOnFailure) {
                throw new IllegalStateException("Failed to run osascript for tab opening", e);
            }
            return false;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            if (throwOnFailure) {
                throw new IllegalStateException("Tab opening was interrupted", e);
            }
            return false;
        }
    }

    private String readOutput(final Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            final StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!output.isEmpty()) {
                    output.append(System.lineSeparator());
                }
                output.append(line);
            }
            return output.toString();
        } catch (final IOException e) {
            return "failed to read process output: " + e.getMessage();
        }
    }
}
