package com.sitionix.forgeagent.infrastructure.codex;

import com.sitionix.forgeagent.infrastructure.local.runtime.ManagedRuntimeProcess;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
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

    private final ObjectMapper objectMapper;
    private final StartedCodexAppServer server;
    private final CodexAppServerProperties properties;
    private final CodexServerRequestHandler serverRequestHandler;
    private final CodexTransportEventHandler eventHandler;
    private final Clock cleanupClock;
    private final Instant cleanupDeadline;
    private final Writer writer;
    private final AtomicLong requestIds = new AtomicLong(1L);
    private final Map<String, PendingRequest> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean invalid = new AtomicBoolean();
    private final AtomicBoolean deadlineKillIssued = new AtomicBoolean();
    private final Object lifecycleLock = new Object();
    private volatile boolean cleanupStarted;
    private volatile boolean cleanupComplete;
    private volatile CodexTransportException cleanupFailure;
    private final Thread stdoutReaderThread;
    private final Thread stderrReaderThread;

    CodexJsonRpcTransport(final ObjectMapper objectMapper,
                          final StartedCodexAppServer server,
                          final CodexAppServerProperties properties) {
        this(objectMapper, server, properties, CodexServerRequestHandler.unsupported());
    }

    CodexJsonRpcTransport(final ObjectMapper objectMapper,
                          final StartedCodexAppServer server,
                          final CodexAppServerProperties properties,
                          final Clock cleanupClock,
                          final Instant cleanupDeadline) {
        this(objectMapper, server, properties, CodexServerRequestHandler.unsupported(),
                CodexTransportEventHandler.noop(), cleanupClock, cleanupDeadline);
    }

    CodexJsonRpcTransport(final ObjectMapper objectMapper,
                          final StartedCodexAppServer server,
                          final CodexAppServerProperties properties,
                          final CodexServerRequestHandler serverRequestHandler) {
        this(objectMapper, server, properties, serverRequestHandler, CodexTransportEventHandler.noop());
    }

    CodexJsonRpcTransport(final ObjectMapper objectMapper,
                          final StartedCodexAppServer server,
                          final CodexAppServerProperties properties,
                          final CodexServerRequestHandler serverRequestHandler,
                          final CodexTransportEventHandler eventHandler) {
        this(objectMapper, server, properties, serverRequestHandler, eventHandler, null, null);
    }

    private CodexJsonRpcTransport(final ObjectMapper objectMapper,
                                  final StartedCodexAppServer server,
                                  final CodexAppServerProperties properties,
                                  final CodexServerRequestHandler serverRequestHandler,
                                  final CodexTransportEventHandler eventHandler,
                                  final Clock cleanupClock,
                                  final Instant cleanupDeadline) {
        this.objectMapper = objectMapper;
        this.server = server;
        this.properties = properties;
        this.serverRequestHandler = serverRequestHandler;
        this.eventHandler = eventHandler;
        this.cleanupClock = cleanupClock;
        this.cleanupDeadline = cleanupDeadline;
        this.writer = new OutputStreamWriter(server.process().getOutputStream(), StandardCharsets.UTF_8);
        this.stdoutReaderThread = Thread.ofVirtual().name("forge-agent-codex-stdout-" + server.process().pid()).unstarted(this::readStdout);
        this.stderrReaderThread = Thread.ofVirtual().name("forge-agent-codex-stderr-" + server.process().pid()).unstarted(this::drainStderr);
        synchronized (this.lifecycleLock) {
            this.stdoutReaderThread.start();
            this.stderrReaderThread.start();
        }
    }

    boolean healthy() {
        return !this.invalid.get() && this.server.process().isAlive();
    }

    boolean cleanupComplete() {
        return this.cleanupComplete;
    }

    JsonNode request(final String method, final JsonNode params, final Duration timeout) {
        return this.request(method, params, timeout, Runnable::run);
    }

    JsonNode request(final String method, final JsonNode params, final Duration timeout,
                     final java.util.function.Consumer<Runnable> dispatch) {
        this.requireHealthy(method);
        final String requestId = Long.toString(this.requestIds.getAndIncrement());
        final CompletableFuture<JsonNode> future = new CompletableFuture<>();
        this.pending.put(requestId, new PendingRequest(method, future));
        final String timeoutMessage = "Codex request timed out method=" + method + " requestId=" + requestId;
        final AtomicBoolean dispatching = new AtomicBoolean(true);
        final RequestDeadline deadline = new RequestDeadline(timeout, () -> {
            if (this.cleanupDeadline == null && dispatching.get() && this.deadlineKillIssued.compareAndSet(false, true)) {
                CodexProcessTree.capture(this.server.process()).terminateTree();
            }
            future.completeExceptionally(new CodexTransportException(timeoutMessage));
        });
        try {
            dispatch.accept(() -> {
                try {
                    this.send(this.requestMessage(method, requestId, params), deadline);
                } catch (IOException exception) {
                    throw new CodexTransportException("Codex request write failed method=" + method, exception);
                }
            });
            dispatching.set(false);
            // Dispatch fencing has ended before waiting for any provider response.
            final JsonNode response = future.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            if (!deadline.complete()) throw new TimeoutException(timeoutMessage);
            return response;
        } catch (final TimeoutException e) {
            deadline.expire();
            this.invalidate("request timeout method=" + method + " requestId=" + requestId, e);
            throw new CodexTransportException(timeoutMessage, e);
        } catch (final ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new CodexTransportException("Codex request failed method=" + method + " requestId=" + requestId, cause);
        } catch (final Exception e) {
            this.invalidate("request failed method=" + method + " requestId=" + requestId, e);
            if (deadline.expired()) throw new CodexTransportException(timeoutMessage, e);
            throw new CodexTransportException("Codex request failed method=" + method + " requestId=" + requestId, e);
        } finally {
            this.pending.remove(requestId);
            deadline.close();
            if (deadline.expired()) this.close();
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
        this.send(message, null);
    }

    private void send(final JsonNode message, final RequestDeadline deadline) throws IOException {
        synchronized (this.writer) {
            final String encoded = this.objectMapper.writeValueAsString(message);
            if (deadline != null) deadline.requireTime();
            this.requireHealthy("write");
            this.writer.write(encoded);
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
        if (message.has("id") && !message.hasNonNull("method")) {
            this.handleResponse(message);
            return;
        }
        if (message.has("id") && message.hasNonNull("method")) {
            this.handleServerRequest(message);
            return;
        }
        if (!message.has("id") && message.hasNonNull("method")) {
            this.eventHandler.handleNotification(message.path("method").asText(""), message.path("params"));
            return;
        }
        throw new CodexTransportException("Codex app-server emitted malformed JSON-RPC message");
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

    private void handleServerRequest(final JsonNode message) {
        final String method = message.path("method").asText("");
        final ObjectNode response = this.objectMapper.createObjectNode();
        response.set("id", message.get("id"));
        try {
            final JsonNode result = this.serverRequestHandler.handle(method, message.path("params"));
            response.set("result", result == null ? NullNode.getInstance() : result);
        } catch (final UnsupportedOperationException e) {
            response.set("error", this.error(-32601, "Codex server request method is not supported."));
        } catch (final RuntimeException e) {
            response.set("error", this.error(-32000, "Codex server request failed."));
        }
        try {
            this.send(response);
        } catch (final IOException e) {
            this.invalidate("server request response failed method=" + method, e);
            throw new CodexTransportException("Codex server request response failed method=" + method, e);
        }
    }

    private ObjectNode error(final int code, final String message) {
        final ObjectNode error = this.objectMapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        return error;
    }

    private void drainStderr() {
        try {
            while (!this.invalid.get()) {
                final String frame = this.readFrame(this.server.process().getErrorStream(), "stderr");
                if (frame == null) {
                    return;
                }
                log.debug("Codex app-server stderr line drained pid={}", this.server.process().pid());
            }
        } catch (final Exception ignored) {
            // Diagnostics only. Never fail a healthy transport from stderr noise.
        }
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
        this.eventHandler.transportFailed(failure);
        this.closeProcess();
    }

    @Override
    public void close() {
        this.invalid.set(true);
        final CodexTransportException failure = new CodexTransportException("Codex app-server transport closed");
        this.pending.forEach((id, request) -> request.future().completeExceptionally(failure));
        this.pending.clear();
        this.eventHandler.transportFailed(failure);
        this.closeProcess();
    }

    private void closeProcess() {
        synchronized (this.lifecycleLock) {
            if (this.cleanupComplete) {
                return;
            }
            final Process process = this.server.process();
            if (process instanceof ManagedRuntimeProcess managed) {
                this.closeManagedProcess(managed);
                return;
            }
            if (this.cleanupStarted) {
                if (!process.isAlive()) {
                    try {
                        this.completeCleanup(process);
                    } catch (final CodexTransportException e) {
                        this.cleanupFailure = e;
                        throw e;
                    }
                    return;
                }
                if (this.cleanupFailure != null) {
                    throw this.cleanupFailure;
                }
                return;
            }
            this.cleanupStarted = true;
            final ProcessHandle rootHandle = nativeHandle(process);
            final Map<Long, OwnedHandle> childProcesses = new HashMap<>();
            captureDescendants(rootHandle, childProcesses);
            try {
                if (process.isAlive()) {
                    captureDescendants(rootHandle, childProcesses);
                    terminateChildFirst(childProcesses, false);
                    destroyRoot(process, rootHandle, false);
                    if (!process.waitFor(this.cleanupWaitMillis(this.properties.getGracefulTerminateTimeout()),
                            TimeUnit.MILLISECONDS)) {
                        captureDescendants(rootHandle, childProcesses);
                        terminateChildFirst(childProcesses, true);
                        destroyRoot(process, rootHandle, true);
                        if (!process.waitFor(this.cleanupWaitMillis(this.properties.getForceKillTimeout()),
                                TimeUnit.MILLISECONDS)) {
                            throw new CodexTransportException("Codex app-server process remained alive after force kill timeout");
                        }
                    }
                }
                if (process.isAlive()) {
                    throw new CodexTransportException("Codex app-server process cleanup incomplete");
                }
                terminateChildFirst(childProcesses, true);
                for (final OwnedHandle child : childProcesses.values()) {
                    if (child.handle().isAlive()) {
                        try {
                            child.handle().onExit().get(this.cleanupWaitMillis(this.properties.getForceKillTimeout()),
                                    TimeUnit.MILLISECONDS);
                        } catch (final java.util.concurrent.TimeoutException exception) {
                            throw new CodexTransportException("Codex child process remained alive after force kill timeout", exception);
                        } catch (final java.util.concurrent.ExecutionException exception) {
                            throw new CodexTransportException("Codex child process cleanup failed", exception.getCause());
                        }
                    }
                }
                // A blocked native write owns this monitor. Killing its pipe owners first unblocks it.
                this.closeStdin();
                this.completeCleanup(process);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                terminateChildFirst(childProcesses, true);
                destroyRoot(process, rootHandle, true);
                this.cleanupFailure = new CodexTransportException("Codex app-server cleanup interrupted", e);
                throw this.cleanupFailure;
            } catch (final CodexTransportException e) {
                this.cleanupFailure = e;
                throw e;
            }
        }
    }

    /** Native pipe handles cannot acknowledge cleanup of a different runtime UID. */
    private void closeManagedProcess(final ManagedRuntimeProcess process) {
        this.cleanupStarted = true;
        try {
            // Required even after pipe exit, and retried after any unconfirmed stop.
            // This runs before touching the writer monitor, so blocked stdin can unwind.
            process.terminateOwnedUnit();
            if (!process.waitFor(this.cleanupWaitMillis(this.properties.getForceKillTimeout()), TimeUnit.MILLISECONDS)) {
                throw new CodexTransportException("Codex pipe remained alive after owned runtime cleanup");
            }
            this.closeStdin();
            this.completeCleanup(process);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            this.cleanupFailure = new CodexTransportException("Codex owned runtime cleanup interrupted", exception);
            throw this.cleanupFailure;
        } catch (final RuntimeException exception) {
            this.cleanupFailure = new CodexTransportException("Codex owned runtime cleanup unconfirmed", exception);
            throw this.cleanupFailure;
        }
    }

    private static ProcessHandle nativeHandle(final Process process) {
        try {
            return process.toHandle();
        } catch (final UnsupportedOperationException exception) {
            // Synthetic Process implementations in unit tests use the Process API fallback below.
            return null;
        }
    }

    private static void captureDescendants(final ProcessHandle root,
                                           final Map<Long, OwnedHandle> descendants) {
        if (root == null || !root.isAlive()) {
            return;
        }
        try {
            root.descendants().forEach(handle -> descendants.merge(
                    handle.pid(), new OwnedHandle(handle, depthFromRoot(handle, root)),
                    (known, discovered) -> known.depth() >= discovered.depth() ? known : discovered));
        } catch (final UnsupportedOperationException ignored) {
            // Root termination remains available even when descendant enumeration is unsupported.
        }
    }

    private static int depthFromRoot(final ProcessHandle handle, final ProcessHandle root) {
        int depth = 1;
        ProcessHandle ancestor = handle;
        while (depth < 64) {
            final var parent = ancestor.parent();
            if (parent.isEmpty() || parent.get().pid() == root.pid()) {
                return depth;
            }
            ancestor = parent.get();
            depth++;
        }
        return depth;
    }

    private static void terminateChildFirst(final Map<Long, OwnedHandle> descendants, final boolean force) {
        descendants.values().stream()
                .sorted((left, right) -> Integer.compare(right.depth(), left.depth()))
                .map(OwnedHandle::handle)
                .filter(ProcessHandle::isAlive)
                .forEach(handle -> {
                    if (force) {
                        handle.destroyForcibly();
                    } else {
                        handle.destroy();
                    }
                });
    }

    private static void destroyRoot(final Process process, final ProcessHandle root, final boolean force) {
        if (root != null) {
            if (force) {
                root.destroyForcibly();
            } else {
                root.destroy();
            }
        } else if (force) {
            process.destroyForcibly();
        } else {
            process.destroy();
        }
    }

    private void completeCleanup(final Process process) {
        if (process.isAlive()) {
            throw new CodexTransportException("Codex app-server process cleanup incomplete");
        }
        try {
            this.joinReader(this.stdoutReaderThread, "stdout");
            this.joinReader(this.stderrReaderThread, "stderr");
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CodexTransportException("Codex app-server cleanup interrupted", e);
        }
        this.cleanupFailure = null;
        this.cleanupComplete = true;
    }

    private void closeStdin() {
        try {
            synchronized (this.writer) {
                this.writer.close();
            }
        } catch (final IOException ignored) {
            // Closing stdin is best-effort during transport teardown.
        }
    }

    private void joinReader(final Thread thread, final String streamName) throws InterruptedException {
        if (thread == Thread.currentThread()) {
            return;
        }
        final long waitMillis = this.cleanupWaitMillis(this.properties.getForceKillTimeout());
        if (waitMillis > 0) {
            thread.join(waitMillis);
        }
        if (thread.isAlive()) {
            throw new CodexTransportException("Codex app-server " + streamName + " reader did not terminate");
        }
    }

    private long cleanupWaitMillis(final Duration maximum) {
        if (this.cleanupClock == null || this.cleanupDeadline == null) {
            return maximum.toMillis();
        }
        final Duration remaining = Duration.between(this.cleanupClock.instant(), this.cleanupDeadline);
        if (remaining.isZero() || remaining.isNegative()) {
            return 0L;
        }
        return Math.min(maximum.toMillis(), remaining.toMillis());
    }

    private record PendingRequest(String method, CompletableFuture<JsonNode> future) {
    }

    private final class RequestDeadline implements AutoCloseable {
        private final long deadlineNanos;
        private final AtomicBoolean resolved = new AtomicBoolean();
        private final AtomicBoolean expired = new AtomicBoolean();
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private final Runnable abort;
        private final Thread watchdog;

        RequestDeadline(final Duration timeout, final Runnable abort) {
            this.deadlineNanos = System.nanoTime() + timeout.toNanos();
            this.abort = abort;
            // Recovery transports already have one authoritative outer lifecycle watchdog.
            if (cleanupDeadline != null) {
                this.watchdog = null;
                return;
            }
            this.watchdog = Thread.ofVirtual().name("forge-agent-codex-deadline-" + server.process().pid()).start(() -> {
                try {
                    this.finished.get(this.remainingNanos(), TimeUnit.NANOSECONDS);
                } catch (TimeoutException exception) {
                    this.expire();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }

        long remainingNanos() throws TimeoutException {
            final long remaining = this.deadlineNanos - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException("Codex request deadline exhausted");
            return remaining;
        }

        boolean complete() {
            if (System.nanoTime() >= this.deadlineNanos) this.expire();
            return this.resolved.compareAndSet(false, true);
        }

        void requireTime() {
            if (System.nanoTime() >= this.deadlineNanos) {
                this.expire();
                // Another expiry owner may not have published expired yet; elapsed time still forbids a write.
                throw new CodexTransportException("Codex request deadline exhausted before write");
            }
            if (this.expired()) throw new CodexTransportException("Codex request deadline exhausted before write");
        }

        void expire() {
            if (this.resolved.compareAndSet(false, true)) {
                this.expired.set(true);
                this.abort.run();
            }
        }

        boolean expired() { return this.expired.get(); }

        @Override public void close() {
            this.resolved.set(true);
            this.finished.complete(null);
            if (this.watchdog == null) return;
            boolean interrupted = Thread.interrupted();
            final long cleanupEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                    Math.max(1, cleanupWaitMillis(properties.getForceKillTimeout())));
            try {
                while (this.watchdog.isAlive() && System.nanoTime() < cleanupEnd) {
                    try {
                        this.watchdog.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(cleanupEnd - System.nanoTime())));
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
                if (this.watchdog.isAlive()) throw new CodexTransportException("Codex request deadline watchdog did not terminate");
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private record OwnedHandle(ProcessHandle handle, int depth) {
    }
}
