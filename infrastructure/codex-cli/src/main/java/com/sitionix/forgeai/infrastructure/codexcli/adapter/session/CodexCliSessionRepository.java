package com.sitionix.forgeai.infrastructure.codexcli.adapter.session;

import com.sitionix.forgeai.domain.repository.CodexSessionRepository;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.java.Log;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Log
@Component
public class CodexCliSessionRepository implements CodexSessionRepository {

    private static final long OUTPUT_WAIT_SLICE_MS = 200L;

    private final List<String> commandTemplate;
    private final Duration defaultOutputTimeout;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public CodexCliSessionRepository(
            @Value("#{'${forge.ai.codex-session.command:codex,--no-alt-screen,-C,{workspaceRoot}}'.split(',')}") final List<String> commandTemplate,
            @Value("${forge.ai.codex-session.output-timeout:PT10M}") final Duration defaultOutputTimeout) {
        this.commandTemplate = commandTemplate;
        this.defaultOutputTimeout = defaultOutputTimeout;
    }

    @Override
    public String start(final String initialPrompt, final String sourceTerminalTty) {
        final String sessionId = UUID.randomUUID().toString();
        final SessionState sessionState = this.createSession(sessionId);
        this.sessions.put(sessionId, sessionState);
        this.writeToSession(sessionState, initialPrompt, sessionId);
        log.info("Codex session started: sessionId=" + sessionId + ", pid=" + sessionState.process.pid());
        return sessionId;
    }

    @Override
    public void send(final String sessionId, final String message, final String sourceTerminalTty) {
        final SessionState sessionState = this.requireActiveSession(sessionId);
        this.writeToSession(sessionState, message, sessionId);
        log.info("Codex session message sent: sessionId=" + sessionId + ", chars=" + message.length());
    }

    @Override
    public String waitForOutput(final String sessionId, final long timeoutMs) {
        final SessionState sessionState = this.requireSession(sessionId);
        final long effectiveTimeoutMs = this.resolveTimeout(timeoutMs);
        final Instant deadline = Instant.now().plusMillis(effectiveTimeoutMs);
        sessionState.lock.lock();
        try {
            while (true) {
                final String output = sessionState.readNewOutput();
                if (!output.isBlank()) {
                    sessionState.lastOutputAt = Instant.now();
                    return output;
                }
                if (!sessionState.process.isAlive()) {
                    throw new IllegalStateException("Codex session process exited before output: sessionId="
                            + sessionId + ", exitCode=" + this.exitCode(sessionState.process));
                }
                final long remainingMs = Duration.between(Instant.now(), deadline).toMillis();
                if (remainingMs <= 0) {
                    throw new IllegalStateException("Timed out waiting for Codex session output: sessionId="
                            + sessionId + ", timeoutMs=" + effectiveTimeoutMs);
                }
                sessionState.outputArrived.await(Math.min(remainingMs, OUTPUT_WAIT_SLICE_MS), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Codex output: sessionId=" + sessionId, e);
        } finally {
            sessionState.lock.unlock();
        }
    }

    @Override
    public boolean isAlive(final String sessionId) {
        final SessionState sessionState = this.sessions.get(sessionId);
        return sessionState != null && sessionState.process.isAlive();
    }

    @Override
    public void close(final String sessionId) {
        final SessionState sessionState = this.sessions.remove(sessionId);
        if (sessionState == null) {
            return;
        }
        this.closeQuietly(sessionState.writer);
        if (sessionState.process.isAlive()) {
            sessionState.process.destroy();
            try {
                if (!sessionState.process.waitFor(2, TimeUnit.SECONDS)) {
                    sessionState.process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sessionState.process.destroyForcibly();
            }
        }
        log.info("Codex session closed: sessionId=" + sessionId);
    }

    private SessionState createSession(final String sessionId) {
        final String workspaceRoot = this.resolveWorkspaceRoot();
        final Process process = this.startProcess(workspaceRoot);
        final SessionState sessionState = new SessionState(
                process,
                new BufferedWriter(new java.io.OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)),
                Instant.now(),
                workspaceRoot
        );
        this.startReader(process.getInputStream(), sessionState);
        this.startReader(process.getErrorStream(), sessionState);
        return sessionState;
    }

    private Process startProcess(final String workspaceRoot) {
        final List<String> command = this.commandTemplate.stream()
                .map(value -> value.replace("{workspaceRoot}", workspaceRoot))
                .toList();
        try {
            return new ProcessBuilder(command)
                    .directory(Paths.get(workspaceRoot).toFile())
                    .redirectErrorStream(false)
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start Codex session process", e);
        }
    }

    private void startReader(final InputStream inputStream, final SessionState sessionState) {
        final Thread thread = new Thread(() -> {
            try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                final char[] buffer = new char[1024];
                int count;
                while ((count = reader.read(buffer)) >= 0) {
                    sessionState.lock.lock();
                    try {
                        sessionState.outputBuffer.append(buffer, 0, count);
                        sessionState.outputArrived.signalAll();
                    } finally {
                        sessionState.lock.unlock();
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, "codex-session-reader-" + sessionState.process.pid());
        thread.setDaemon(true);
        thread.start();
    }

    private void writeToSession(final SessionState sessionState, final String message, final String sessionId) {
        if (!sessionState.process.isAlive()) {
            throw new IllegalStateException("Codex session is not alive: sessionId=" + sessionId
                    + ", exitCode=" + this.exitCode(sessionState.process));
        }
        try {
            sessionState.writer.write(message);
            sessionState.writer.write(System.lineSeparator());
            sessionState.writer.flush();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write message to Codex session: sessionId=" + sessionId, e);
        }
    }

    private SessionState requireSession(final String sessionId) {
        final SessionState sessionState = this.sessions.get(sessionId);
        if (sessionState == null) {
            throw new IllegalStateException("Codex session not found: sessionId=" + sessionId);
        }
        return sessionState;
    }

    private SessionState requireActiveSession(final String sessionId) {
        final SessionState sessionState = this.requireSession(sessionId);
        if (!sessionState.process.isAlive()) {
            throw new IllegalStateException("Codex session is not alive: sessionId=" + sessionId
                    + ", exitCode=" + this.exitCode(sessionState.process));
        }
        return sessionState;
    }

    private long resolveTimeout(final long requestedTimeoutMs) {
        if (requestedTimeoutMs > 0) {
            return requestedTimeoutMs;
        }
        return this.defaultOutputTimeout.toMillis();
    }

    private String resolveWorkspaceRoot() {
        final String envWorkspaceRoot = System.getenv("WORKSPACE_ROOT");
        if (envWorkspaceRoot != null && !envWorkspaceRoot.isBlank()) {
            return Paths.get(envWorkspaceRoot).toAbsolutePath().normalize().toString();
        }
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize().toString();
    }

    private Integer exitCode(final Process process) {
        if (process.isAlive()) {
            return null;
        }
        return process.exitValue();
    }

    private void closeQuietly(final Writer writer) {
        try {
            writer.close();
        } catch (IOException ignored) {
            // ignored
        }
    }

    private static final class SessionState {
        private final Process process;
        private final BufferedWriter writer;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition outputArrived = this.lock.newCondition();
        private final StringBuilder outputBuffer = new StringBuilder();
        private int readCursor;
        private final Instant startedAt;
        private final String workspaceRoot;
        private Instant lastOutputAt;

        private SessionState(final Process process,
                             final BufferedWriter writer,
                             final Instant startedAt,
                             final String workspaceRoot) {
            this.process = process;
            this.writer = writer;
            this.startedAt = startedAt;
            this.workspaceRoot = workspaceRoot;
            this.lastOutputAt = startedAt;
        }

        private String readNewOutput() {
            if (this.readCursor >= this.outputBuffer.length()) {
                return "";
            }
            final String output = this.outputBuffer.substring(this.readCursor);
            this.readCursor = this.outputBuffer.length();
            return output;
        }
    }
}
