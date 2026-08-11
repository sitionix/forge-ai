package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class CodexJsonRpcTransport implements AutoCloseable {

    private static final int STDERR_MAX_LINES = 50;
    private static final int STDERR_MAX_CHARS = 512;

    private final ObjectMapper objectMapper;
    private final StartedCodexAppServer server;
    private final CodexAppServerProperties properties;
    private final Writer writer;
    private final AtomicLong requestIds = new AtomicLong(1L);
    private final Map<String, PendingRequest> pending = new ConcurrentHashMap<>();
    private final List<String> stderrTail = new ArrayList<>();
    private final AtomicBoolean invalid = new AtomicBoolean();
    private final Thread stdoutReaderThread;
    private final Thread stderrReaderThread;

    CodexJsonRpcTransport(final ObjectMapper objectMapper,
                          final StartedCodexAppServer server,
                          final CodexAppServerProperties properties) {
        this.objectMapper = objectMapper;
        this.server = server;
        this.properties = properties;
        this.writer = new OutputStreamWriter(server.process().getOutputStream(), StandardCharsets.UTF_8);
        this.stdoutReaderThread = Thread.ofVirtual().name("forge-agent-codex-stdout-" + server.process().pid()).start(this::readStdout);
        this.stderrReaderThread = Thread.ofVirtual().name("forge-agent-codex-stderr-" + server.process().pid()).start(this::drainStderr);
    }

    String codexVersion() {
        return this.server.codexVersion();
    }

    boolean healthy() {
        return !this.invalid.get() && this.server.process().isAlive();
    }

    JsonNode request(final String method, final JsonNode params, final Duration timeout) {
        this.requireHealthy(method);
        final String requestId = Long.toString(this.requestIds.getAndIncrement());
        final CompletableFuture<JsonNode> future = new CompletableFuture<>();
        this.pending.put(requestId, new PendingRequest(method, future));
        try {
            this.send(this.requestMessage(method, requestId, params));
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final TimeoutException e) {
            this.invalidate("request timeout method=" + method + " requestId=" + requestId, e);
            throw new CodexTransportException("Codex request timed out method=" + method + " requestId=" + requestId, e);
        } catch (final ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new CodexTransportException("Codex request failed method=" + method + " requestId=" + requestId, cause);
        } catch (final Exception e) {
            this.invalidate("request failed method=" + method + " requestId=" + requestId, e);
            throw new CodexTransportException("Codex request failed method=" + method + " requestId=" + requestId, e);
        } finally {
            this.pending.remove(requestId);
        }
    }

    void notify(final String method, final JsonNode params) {
        this.requireHealthy(method);
        final ObjectNode notification = this.objectMapper.createObjectNode();
        notification.put("method", method);
        if (params != null && !params.isEmpty()) {
            notification.set("params", params);
        }
        try {
            this.send(notification);
        } catch (final IOException e) {
            this.invalidate("notification failed method=" + method, e);
            throw new CodexTransportException("Codex notification failed method=" + method, e);
        }
    }

    private ObjectNode requestMessage(final String method, final String requestId, final JsonNode params) {
        final ObjectNode request = this.objectMapper.createObjectNode();
        request.put("id", requestId);
        request.put("method", method);
        if (params != null && !params.isEmpty()) {
            request.set("params", params);
        }
        return request;
    }

    private void send(final JsonNode message) throws IOException {
        synchronized (this.writer) {
            this.writer.write(this.objectMapper.writeValueAsString(message));
            this.writer.write('\n');
            this.writer.flush();
        }
    }

    private void readStdout() {
        try {
            while (!this.invalid.get()) {
                final String frame = this.readFrame(this.server.process().getInputStream(), "stdout");
                if (frame == null) {
                    this.invalidate("Codex app-server stdout closed", null);
                    return;
                }
                if (frame.isBlank()) {
                    continue;
                }
                this.handleFrame(frame);
            }
        } catch (final CodexTransportException e) {
            this.invalidate(e.getMessage(), e);
        } catch (final Exception e) {
            this.invalidate("Codex app-server stdout reader failed", e);
        }
    }

    private String readFrame(final InputStream stream, final String streamName) throws IOException {
        final int limit = this.properties.getStdioFrameLimitBytes();
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(8192, limit));
        while (true) {
            final int next = stream.read();
            if (next == -1) {
                if (buffer.size() == 0) {
                    return null;
                }
                throw new CodexTransportException("Codex app-server " + streamName + " closed mid-frame");
            }
            if (next == '\n') {
                return buffer.toString(StandardCharsets.UTF_8);
            }
            if (buffer.size() >= limit) {
                throw new CodexTransportException("Codex app-server " + streamName + " JSON-RPC frame exceeded configured limit bytes=" + limit);
            }
            buffer.write(next);
        }
    }

    private void handleFrame(final String frame) {
        final JsonNode message;
        try {
            message = this.objectMapper.readTree(frame);
        } catch (final JsonProcessingException e) {
            throw new CodexTransportException("Codex app-server emitted malformed JSON-RPC frame", e);
        }
        if (message.hasNonNull("id") && !message.hasNonNull("method")) {
            this.handleResponse(message);
            return;
        }
        if (message.hasNonNull("method")) {
            log.debug("Ignoring Codex JSON-RPC notification/server request method={}", message.path("method").asText(""));
        }
    }

    private void handleResponse(final JsonNode message) {
        final String requestId = message.path("id").asText();
        final PendingRequest pendingRequest = this.pending.remove(requestId);
        if (pendingRequest == null) {
            log.debug("Ignoring unmatched Codex JSON-RPC response requestId={}", requestId);
            return;
        }
        if (message.hasNonNull("error")) {
            final JsonNode error = message.path("error");
            pendingRequest.future().completeExceptionally(new CodexRemoteException(
                    pendingRequest.method(),
                    requestId,
                    error.hasNonNull("code") ? error.get("code").asInt() : null,
                    error.path("message").asText("")
            ));
            return;
        }
        pendingRequest.future().complete(message.path("result"));
    }

    private void drainStderr() {
        try {
            while (!this.invalid.get()) {
                final String frame = this.readFrame(this.server.process().getErrorStream(), "stderr");
                if (frame == null) {
                    return;
                }
                this.recordStderr(frame);
            }
        } catch (final Exception ignored) {
            // Diagnostics only. Never fail a healthy transport from stderr noise.
        }
    }

    private void recordStderr(final String line) {
        synchronized (this.stderrTail) {
            if (this.stderrTail.size() >= STDERR_MAX_LINES) {
                this.stderrTail.remove(0);
            }
            this.stderrTail.add(this.truncate(line));
        }
        log.debug("Codex app-server stderr line captured pid={}", this.server.process().pid());
    }

    private String truncate(final String value) {
        if (value == null || value.length() <= STDERR_MAX_CHARS) {
            return value;
        }
        return value.substring(0, STDERR_MAX_CHARS) + "...";
    }

    private void requireHealthy(final String method) {
        if (!this.healthy()) {
            throw new CodexTransportException("Codex app-server transport is not healthy method=" + method);
        }
    }

    private void invalidate(final String reason, final Throwable cause) {
        if (!this.invalid.compareAndSet(false, true)) {
            return;
        }
        log.warn("Invalidating Codex app-server transport pid={} reason={}", this.server.process().pid(), reason);
        final CodexTransportException failure = cause == null
                ? new CodexTransportException(reason)
                : new CodexTransportException(reason, cause);
        this.pending.forEach((id, request) -> request.future().completeExceptionally(failure));
        this.pending.clear();
        this.closeProcess();
    }

    @Override
    public void close() {
        this.invalid.set(true);
        this.pending.forEach((id, request) -> request.future().completeExceptionally(new CodexTransportException("Codex app-server transport closed")));
        this.pending.clear();
        this.closeProcess();
    }

    private void closeProcess() {
        final Process process = this.server.process();
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(this.properties.getGracefulTerminateTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(this.properties.getForceKillTimeout().toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private record PendingRequest(String method, CompletableFuture<JsonNode> future) {
    }
}
