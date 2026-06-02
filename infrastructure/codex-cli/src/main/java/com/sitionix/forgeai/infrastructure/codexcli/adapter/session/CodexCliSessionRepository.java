package com.sitionix.forgeai.infrastructure.codexcli.adapter.session;

import com.sitionix.forgeai.domain.repository.CodexSessionRepository;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.TerminalTabLauncher;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

@Log
@Component
@RequiredArgsConstructor
public class CodexCliSessionRepository implements CodexSessionRepository {

    private static final long POLL_INTERVAL_MS = 250L;

    private final TerminalTabLauncher terminalTabLauncher;
    private final Map<String, String> lastSnapshots = new ConcurrentHashMap<>();

    @Override
    public String start(final String initialPrompt, final String sourceTerminalTty) {
        final String sessionId = this.createSessionId();
        final String workspaceRoot = this.resolveWorkspaceRoot();
        this.runTmux(List.of("tmux", "new-session", "-d", "-s", sessionId, "-c", workspaceRoot, "codex", "--no-alt-screen", "-C", workspaceRoot), null);
        this.lastSnapshots.put(sessionId, "");
        this.launchVisibleSession(sessionId, sourceTerminalTty);
        if (initialPrompt != null && !initialPrompt.isBlank()) {
            this.send(sessionId, initialPrompt, sourceTerminalTty);
        }
        return sessionId;
    }

    @Override
    public void send(final String sessionId, final String message, final String sourceTerminalTty) {
        this.assertAlive(sessionId);
        this.loadBuffer(message);
        this.runTmux(List.of("tmux", "paste-buffer", "-d", "-t", sessionId), null);
        this.runTmux(List.of("tmux", "send-keys", "-t", sessionId, "Enter"), null);
    }

    @Override
    public String waitForOutput(final String sessionId, final long timeoutMs) {
        this.assertAlive(sessionId);
        final long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
        final String previous = this.lastSnapshots.getOrDefault(sessionId, "");
        while (System.nanoTime() < deadline) {
            final String current = this.capturePane(sessionId);
            if (!current.equals(previous)) {
                this.lastSnapshots.put(sessionId, current);
                if (current.startsWith(previous)) {
                    return current.substring(previous.length()).trim();
                }
                return current;
            }
            this.sleep();
        }
        throw new IllegalStateException("Timed out waiting for Codex output for sessionId=" + sessionId);
    }

    @Override
    public boolean isAlive(final String sessionId) {
        try {
            this.runTmux(List.of("tmux", "has-session", "-t", sessionId), null);
            return true;
        } catch (final IllegalStateException ex) {
            return false;
        }
    }

    @Override
    public void close(final String sessionId) {
        try {
            this.runTmux(List.of("tmux", "kill-session", "-t", sessionId), null);
        } finally {
            this.lastSnapshots.remove(sessionId);
        }
    }

    private void launchVisibleSession(final String sessionId, final String sourceTerminalTty) {
        if (sourceTerminalTty == null || sourceTerminalTty.isBlank()) {
            return;
        }
        this.terminalTabLauncher.launch("tmux attach -t " + this.shellQuote(sessionId), sourceTerminalTty);
    }

    private String createSessionId() {
        return "forge-ai-codex-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String resolveWorkspaceRoot() {
        final String envWorkspaceRoot = System.getenv("WORKSPACE_ROOT");
        if (envWorkspaceRoot != null && !envWorkspaceRoot.isBlank()) {
            return envWorkspaceRoot;
        }
        return System.getProperty("user.dir", ".");
    }

    private void assertAlive(final String sessionId) {
        if (!this.isAlive(sessionId)) {
            throw new IllegalStateException("Codex tmux session is not alive: " + sessionId);
        }
    }

    private void loadBuffer(final String message) {
        this.runTmux(List.of("tmux", "load-buffer", "-"), message);
    }

    private String capturePane(final String sessionId) {
        return this.runTmux(List.of("tmux", "capture-pane", "-pt", sessionId, "-S", "-3000"), null);
    }

    private void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Codex output", e);
        }
    }

    private String runTmux(final List<String> command, final String stdin) {
        final ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        try {
            final Process process = processBuilder.start();
            if (stdin != null) {
                try (var output = process.getOutputStream()) {
                    output.write(stdin.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
            }
            final String output = this.readOutput(process.getInputStream());
            final int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("tmux command failed: " + String.join(" ", command) + "\n" + output);
            }
            return output;
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to run tmux command: " + String.join(" ", command), e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("tmux command interrupted: " + String.join(" ", command), e);
        }
    }

    private String readOutput(final InputStream inputStream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
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
            return "failed to read tmux output: " + e.getMessage();
        }
    }

    private String shellQuote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
