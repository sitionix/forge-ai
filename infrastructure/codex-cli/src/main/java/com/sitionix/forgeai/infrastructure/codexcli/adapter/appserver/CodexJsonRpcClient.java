package com.sitionix.forgeai.infrastructure.codexcli.adapter.appserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitionix.forgeai.domain.model.codex.CodexProgressEvent;
import com.sitionix.forgeai.domain.model.codex.CodexProgressEventType;
import com.sitionix.forgeai.domain.repository.CodexProgressObserver;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class CodexJsonRpcClient implements AutoCloseable {

    private static final int STDERR_TAIL_LINES = 200;
    private static final Duration TURN_WAIT_SLICE = Duration.ofMillis(250);

    private final ObjectMapper objectMapper;
    private final Process process;
    private final List<String> command;
    private final String codexVersion;
    private final Writer writer;
    private final AtomicLong requestIdSequence = new AtomicLong(1L);
    private final Map<String, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, RequestContext> requestContexts = new ConcurrentHashMap<>();
    private final CodexTurnEventCollector turnEventCollector = new CodexTurnEventCollector();
    private final Thread stdoutReaderThread;
    private final Thread stderrReaderThread;
    private final ArrayDeque<String> stderrTail = new ArrayDeque<>();
    private final CodexProgressObserver progressObserver;
    private final CodexProgressProperties progressProperties;
    private final ProgressContext progressContext;
    private volatile boolean initializeSucceeded;
    private volatile boolean initializedSent;
    private volatile String threadId;
    private volatile String activeStepId;
    private volatile Integer activeStepOrder;
    private volatile String activeStepTitle;

    CodexJsonRpcClient(final ObjectMapper objectMapper,
                       final Process process,
                       final List<String> command,
                       final String codexVersion,
                       final CodexProgressObserver progressObserver,
                       final CodexProgressProperties progressProperties,
                       final ProgressContext progressContext) {
        this.objectMapper = objectMapper;
        this.process = process;
        this.command = List.copyOf(command);
        this.codexVersion = codexVersion;
        this.progressObserver = progressObserver;
        this.progressProperties = progressProperties;
        this.progressContext = progressContext;
        this.writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        this.stdoutReaderThread = Thread.ofVirtual().name("codex-appserver-stdout-" + process.pid()).start(this::readStdoutLoop);
        this.stderrReaderThread = Thread.ofVirtual().name("codex-appserver-stderr-" + process.pid()).start(this::readStderrLoop);
    }

    JsonNode request(final String method, final JsonNode params, final Duration timeout) {
        final String requestId = Long.toString(this.requestIdSequence.getAndIncrement());
        final List<String> paramKeys = this.paramKeys(params);
        final CompletableFuture<JsonNode> future = new CompletableFuture<>();
        this.pendingRequests.put(requestId, future);
        this.requestContexts.put(requestId, new RequestContext(method, paramKeys));
        try {
            this.sendMessage(this.buildRequest(method, requestId, params));
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final ExecutionException e) {
            final Throwable cause = e.getCause();
            this.pendingRequests.remove(requestId);
            this.requestContexts.remove(requestId);
            if (cause instanceof CodexAppServerRequestException requestException) {
                throw requestException;
            }
            throw this.protocolError(method, requestId, paramKeys, cause == null ? e.getMessage() : cause.getMessage(), cause == null ? e : cause);
        } catch (final Exception e) {
            this.pendingRequests.remove(requestId);
            this.requestContexts.remove(requestId);
            throw this.protocolError(method, requestId, paramKeys, e.getMessage(), e);
        }
    }

    void notify(final String method, final JsonNode params) {
        try {
            final ObjectNode notification = this.objectMapper.createObjectNode();
            notification.put("method", method);
            if (params != null && !params.isEmpty()) {
                notification.set("params", params);
            }
            this.sendMessage(notification);
        } catch (final IOException e) {
            throw this.protocolError(method, "", this.paramKeys(params), e.getMessage(), e);
        }
    }

    void markInitializeSucceeded() {
        this.initializeSucceeded = true;
    }

    void markInitializedSent() {
        this.initializedSent = true;
    }

    void setThreadId(final String threadId) {
        this.threadId = threadId;
    }

    void setActiveStepContext(final String stepId, final Integer stepOrder, final String stepTitle) {
        this.activeStepId = stepId;
        this.activeStepOrder = stepOrder;
        this.activeStepTitle = stepTitle;
    }

    CodexTurnEventCollector.CompletedTurn awaitCompletedTurn(final String threadId,
                                                             final String turnId,
                                                             final String stepId,
                                                             final Integer stepOrder,
                                                             final String stepTitle,
                                                             final Duration timeout) {
        final long deadlineNanos = System.nanoTime() + timeout.toNanos();
        Instant lastHeartbeatEventAt = null;
        while (true) {
            final CodexTurnEventCollector.TurnSnapshot snapshot = this.turnEventCollector.snapshot(threadId, turnId);
            if (snapshot.completed()) {
                return this.turnEventCollector.awaitCompletedTurn(threadId, turnId, Duration.ofMillis(1));
            }
            final long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                throw new IllegalStateException("Timed out waiting for completed Codex threadId=" + threadId + ", turnId=" + turnId);
            }
            if (this.progressProperties.isEnabled()
                    && this.progressProperties.getHeartbeatInterval() != null
                    && snapshot.lastEventAt() != null
                    && (lastHeartbeatEventAt == null || snapshot.lastEventAt().equals(lastHeartbeatEventAt))
                    && Duration.between(snapshot.lastEventAt(), Instant.now()).compareTo(this.progressProperties.getHeartbeatInterval()) >= 0) {
                lastHeartbeatEventAt = snapshot.lastEventAt();
                this.emit(CodexProgressEvent.builder()
                        .executionId(this.progressContext.executionId())
                        .ticketId(this.progressContext.ticketId())
                        .laneId(this.progressContext.laneId())
                        .agentId(this.progressContext.agentId())
                        .scope(this.progressContext.scope())
                        .sessionId(this.progressContext.sessionId())
                        .threadId(threadId)
                        .turnId(turnId)
                        .stepId(stepId)
                        .stepOrder(stepOrder)
                        .stepTitle(stepTitle)
                        .eventType(CodexProgressEventType.HEARTBEAT)
                        .status("waitingFor=codex-events")
                        .text("elapsed=" + Duration.between(snapshot.lastEventAt(), Instant.now()) + " lastEventAt=" + snapshot.lastEventAt())
                        .occurredAt(Instant.now())
                        .build());
            }
            this.turnEventCollector.awaitUpdate(TURN_WAIT_SLICE);
        }
    }

    void interruptTurn(final String threadId, final String turnId, final Duration timeout) {
        this.emit(CodexProgressEvent.builder()
                .executionId(this.progressContext.executionId())
                .ticketId(this.progressContext.ticketId())
                .laneId(this.progressContext.laneId())
                .agentId(this.progressContext.agentId())
                .scope(this.progressContext.scope())
                .sessionId(this.progressContext.sessionId())
                .threadId(threadId)
                .turnId(turnId)
                .processPid(this.process.pid())
                .eventType(CodexProgressEventType.TURN_INTERRUPT_SENT)
                .occurredAt(Instant.now())
                .build());
        final ObjectNode params = this.objectMapper.createObjectNode();
        params.put("threadId", threadId);
        params.put("turnId", turnId);
        this.request("turn/interrupt", params, timeout);
        final CodexTurnEventCollector.CompletedTurn completedTurn = this.awaitCompletedTurn(threadId, turnId, null, null, null, timeout);
        final String status = completedTurn.turn().path("status").asText("");
        if ("interrupted".equalsIgnoreCase(status)) {
            this.emit(CodexProgressEvent.builder()
                    .executionId(this.progressContext.executionId())
                    .ticketId(this.progressContext.ticketId())
                    .laneId(this.progressContext.laneId())
                    .agentId(this.progressContext.agentId())
                    .scope(this.progressContext.scope())
                    .sessionId(this.progressContext.sessionId())
                    .threadId(threadId)
                    .turnId(turnId)
                    .processPid(this.process.pid())
                    .eventType(CodexProgressEventType.TURN_INTERRUPTED)
                    .status(status)
                    .occurredAt(Instant.now())
                    .build());
        }
    }

    private ObjectNode buildRequest(final String method, final String requestId, final JsonNode params) {
        final ObjectNode request = this.objectMapper.createObjectNode();
        request.put("id", requestId);
        request.put("method", method);
        if (params != null && !params.isEmpty()) {
            request.set("params", params);
        }
        return request;
    }

    private void sendMessage(final JsonNode message) throws IOException {
        synchronized (this.writer) {
            this.writer.write(this.objectMapper.writeValueAsString(message));
            this.writer.write('\n');
            this.writer.flush();
        }
    }

    private void readStdoutLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                final JsonNode message = this.objectMapper.readTree(line);
                if (message.hasNonNull("id") && !message.hasNonNull("method")) {
                    this.handleResponse(message);
                    continue;
                }
                if (message.hasNonNull("method") && message.hasNonNull("id")) {
                    this.handleServerRequest(message);
                    continue;
                }
                if (message.hasNonNull("method")) {
                    this.handleNotification(message);
                }
            }
            this.failPendingRequests("app-server stdout closed");
        } catch (final IOException e) {
            this.failPendingRequests(e.getMessage());
        }
    }

    private void readStderrLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(this.process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (this.stderrTail) {
                    if (this.stderrTail.size() >= STDERR_TAIL_LINES) {
                        this.stderrTail.removeFirst();
                    }
                    this.stderrTail.addLast(line);
                }
                this.emit(CodexProgressEvent.builder()
                        .executionId(this.progressContext.executionId())
                        .ticketId(this.progressContext.ticketId())
                        .laneId(this.progressContext.laneId())
                        .agentId(this.progressContext.agentId())
                        .scope(this.progressContext.scope())
                        .sessionId(this.progressContext.sessionId())
                        .threadId(this.threadId)
                        .processPid(this.process.pid())
                        .eventType(CodexProgressEventType.PROCESS_STDERR)
                        .text(line)
                        .occurredAt(Instant.now())
                        .build());
            }
        } catch (final IOException ignored) {
            // Best-effort diagnostics only.
        }
    }

    private void handleResponse(final JsonNode message) {
        final String requestId = message.path("id").asText();
        final CompletableFuture<JsonNode> future = this.pendingRequests.remove(requestId);
        final RequestContext requestContext = this.requestContexts.remove(requestId);
        if (future == null) {
            return;
        }
        if (message.hasNonNull("error")) {
            final JsonNode error = message.get("error");
            future.completeExceptionally(CodexAppServerRequestException.requestError(
                    requestContext == null ? "unknown" : requestContext.method(),
                    requestId,
                    requestContext == null ? List.of() : requestContext.paramKeys(),
                    error.hasNonNull("code") ? error.get("code").asInt() : null,
                    error.path("message").asText(""),
                    error.has("data") ? error.get("data").toString() : "",
                    this.stderrTail(),
                    this.processExitStatus(),
                    this.initializeSucceeded,
                    this.initializedSent,
                    this.codexVersion,
                    this.command
            ));
            return;
        }
        future.complete(message.path("result"));
    }

    private void handleServerRequest(final JsonNode message) {
        final String method = message.path("method").asText();
        this.emit(CodexProgressEvent.builder()
                .executionId(this.progressContext.executionId())
                .ticketId(this.progressContext.ticketId())
                .laneId(this.progressContext.laneId())
                .agentId(this.progressContext.agentId())
                .scope(this.progressContext.scope())
                .sessionId(this.progressContext.sessionId())
                .threadId(this.threadId)
                .processPid(this.process.pid())
                .eventType(CodexProgressEventType.SERVER_REQUEST)
                .status(method)
                .text(this.truncate(message.path("params").toString(), this.progressProperties.getCommandOutputMaxCharsPerLine()))
                .occurredAt(Instant.now())
                .build());
        this.rejectServerRequest(message, method);
    }

    private void rejectServerRequest(final JsonNode message, final String method) {
        final ObjectNode response = this.objectMapper.createObjectNode();
        response.set("id", message.path("id"));
        final ObjectNode error = response.putObject("error");
        error.put("code", -32000);
        error.put("message", "Server-initiated tool calls are disabled in supervised headless Forge AI lane execution. Use non-interactive shell commands only.");
        error.put("data", "Rejected app-server request method=" + method);
        try {
            this.sendMessage(response);
        } catch (final IOException e) {
            throw this.protocolError(method, message.path("id").asText(""), List.of("serverRequest"), e.getMessage(), e);
        }
    }

    private void handleNotification(final JsonNode message) {
        final String method = message.path("method").asText();
        final JsonNode params = message.path("params");
        switch (method) {
            case "turn/started" -> this.handleTurnStarted(params);
            case "turn/plan/updated" -> this.handlePlanUpdated(params);
            case "item/started" -> this.handleItemStarted(params);
            case "item/completed" -> this.handleItemCompleted(params);
            case "item/agentMessage/delta" -> this.handleAgentMessageDelta(params);
            case "item/commandExecution/outputDelta", "command/exec/outputDelta", "process/outputDelta" -> this.handleCommandOutputDelta(params);
            case "turn/diff/updated" -> this.handleDiffUpdated(params);
            case "turn/completed" -> this.handleTurnCompleted(params);
            default -> {
                // ignore other notifications
            }
        }
    }

    private void handleTurnStarted(final JsonNode params) {
        final String eventThreadId = params.path("threadId").asText(this.threadId);
        final JsonNode turnNode = params.path("turn");
        final String turnId = turnNode.path("id").asText(null);
        this.turnEventCollector.registerTurnStarted(eventThreadId, turnId, turnNode);
        this.emit(CodexProgressEvent.builder()
                .executionId(this.progressContext.executionId())
                .ticketId(this.progressContext.ticketId())
                .laneId(this.progressContext.laneId())
                .agentId(this.progressContext.agentId())
                .scope(this.progressContext.scope())
                .sessionId(this.progressContext.sessionId())
                .threadId(eventThreadId)
                .turnId(turnId)
                .stepId(this.activeStepId)
                .stepOrder(this.activeStepOrder)
                .stepTitle(this.activeStepTitle)
                .processPid(this.process.pid())
                .eventType(CodexProgressEventType.TURN_STARTED)
                .status(turnNode.path("status").asText(null))
                .occurredAt(Instant.now())
                .build());
    }

    private void handlePlanUpdated(final JsonNode params) {
        final String eventThreadId = params.path("threadId").asText(this.threadId);
        final String turnId = params.path("turnId").asText(null);
        this.turnEventCollector.touch(eventThreadId, turnId);
        final String text = params.hasNonNull("explanation")
                ? params.get("explanation").asText()
                : params.path("plan").isArray() && !params.path("plan").isEmpty()
                ? params.path("plan").get(0).path("step").asText("")
                : "";
        this.emit(CodexProgressEvent.builder()
                .executionId(this.progressContext.executionId())
                .ticketId(this.progressContext.ticketId())
                .laneId(this.progressContext.laneId())
                .agentId(this.progressContext.agentId())
                .scope(this.progressContext.scope())
                .sessionId(this.progressContext.sessionId())
                .threadId(eventThreadId)
                .turnId(turnId)
                .processPid(this.process.pid())
                .eventType(CodexProgressEventType.TURN_PLAN_UPDATED)
                .text(this.truncate(text, this.progressProperties.getAgentMessageMaxCharsPerLine()))
                .occurredAt(Instant.now())
                .build());
    }

    private void handleItemStarted(final JsonNode params) {
        final String eventThreadId = params.path("threadId").asText(this.threadId);
        final String turnId = params.path("turnId").asText(null);
        final JsonNode item = params.path("item");
        final String itemType = item.path("type").asText();
        final String itemId = item.path("id").asText(null);
        this.turnEventCollector.touch(eventThreadId, turnId);
        if ("commandExecution".equals(itemType)) {
            this.emit(CodexProgressEvent.builder()
                    .executionId(this.progressContext.executionId())
                    .ticketId(this.progressContext.ticketId())
                    .laneId(this.progressContext.laneId())
                    .agentId(this.progressContext.agentId())
                    .scope(this.progressContext.scope())
                    .sessionId(this.progressContext.sessionId())
                    .threadId(eventThreadId)
                    .turnId(turnId)
                    .itemId(itemId)
                    .processPid(this.process.pid())
                    .eventType(CodexProgressEventType.COMMAND_STARTED)
                    .command(item.path("command").asText(null))
                    .cwd(item.path("cwd").asText(null))
                    .status(item.path("status").asText(null))
                    .occurredAt(Instant.now())
                    .build());
        }
    }

    private void handleItemCompleted(final JsonNode params) {
        final String eventThreadId = params.path("threadId").asText(this.threadId);
        final String turnId = params.path("turnId").asText(null);
        final JsonNode item = params.path("item");
        final String itemType = item.path("type").asText();
        this.turnEventCollector.registerCompletedItem(eventThreadId, turnId, item);
        if ("commandExecution".equals(itemType)) {
            this.emit(CodexProgressEvent.builder()
                    .executionId(this.progressContext.executionId())
                    .ticketId(this.progressContext.ticketId())
                    .laneId(this.progressContext.laneId())
                    .agentId(this.progressContext.agentId())
                    .scope(this.progressContext.scope())
                    .sessionId(this.progressContext.sessionId())
                    .threadId(eventThreadId)
                    .turnId(turnId)
                    .itemId(item.path("id").asText(null))
                    .processPid(this.process.pid())
                    .eventType(CodexProgressEventType.COMMAND_COMPLETED)
                    .command(item.path("command").asText(null))
                    .cwd(item.path("cwd").asText(null))
                    .status(item.path("status").asText(null))
                    .durationMs(item.hasNonNull("durationMs") ? item.get("durationMs").asLong() : null)
                    .occurredAt(Instant.now())
                    .build());
            return;
        }
        if ("fileChange".equals(itemType)) {
            final JsonNode changes = item.path("changes");
            this.emit(CodexProgressEvent.builder()
                    .executionId(this.progressContext.executionId())
                    .ticketId(this.progressContext.ticketId())
                    .laneId(this.progressContext.laneId())
                    .agentId(this.progressContext.agentId())
                    .scope(this.progressContext.scope())
                    .sessionId(this.progressContext.sessionId())
                    .threadId(eventThreadId)
                    .turnId(turnId)
                    .itemId(item.path("id").asText(null))
                    .processPid(this.process.pid())
                    .eventType(CodexProgressEventType.FILE_CHANGE)
                    .status(item.path("status").asText(null))
                    .fileCount(changes.isArray() ? changes.size() : 0)
                    .occurredAt(Instant.now())
                    .build());
        }
    }

    private void handleAgentMessageDelta(final JsonNode params) {
        final String eventThreadId = params.path("threadId").asText(this.threadId);
        final String turnId = params.path("turnId").asText(null);
        this.turnEventCollector.touch(eventThreadId, turnId);
        if (!this.progressProperties.isLogAgentMessageDeltas()) {
            return;
        }
        final String delta = params.path("delta").asText("");
        this.emit(CodexProgressEvent.builder()
                .executionId(this.progressContext.executionId())
                .ticketId(this.progressContext.ticketId())
                .laneId(this.progressContext.laneId())
                .agentId(this.progressContext.agentId())
                .scope(this.progressContext.scope())
                .sessionId(this.progressContext.sessionId())
                .threadId(eventThreadId)
                .turnId(turnId)
                .itemId(params.path("itemId").asText(null))
                .processPid(this.process.pid())
                .eventType(CodexProgressEventType.AGENT_MESSAGE_DELTA)
                .chars(delta.length())
                .text(this.truncate(delta, this.progressProperties.getAgentMessageMaxCharsPerLine()))
                .occurredAt(Instant.now())
                .build());
    }

    private void handleCommandOutputDelta(final JsonNode params) {
        final String eventThreadId = params.path("threadId").asText(this.threadId);
        final String turnId = params.path("turnId").asText(null);
        this.turnEventCollector.touch(eventThreadId, turnId);
        if (!this.progressProperties.isLogCommandOutputDeltas()) {
            return;
        }
        final String delta = params.path("delta").asText("");
        this.emit(CodexProgressEvent.builder()
                .executionId(this.progressContext.executionId())
                .ticketId(this.progressContext.ticketId())
                .laneId(this.progressContext.laneId())
                .agentId(this.progressContext.agentId())
                .scope(this.progressContext.scope())
                .sessionId(this.progressContext.sessionId())
                .threadId(eventThreadId)
                .turnId(turnId)
                .itemId(params.path("itemId").asText(null))
                .processPid(this.process.pid())
                .eventType(CodexProgressEventType.COMMAND_OUTPUT)
                .stream("merged")
                .chars(delta.length())
                .text(this.truncate(delta, this.progressProperties.getCommandOutputMaxCharsPerLine()))
                .occurredAt(Instant.now())
                .build());
    }

    private void handleDiffUpdated(final JsonNode params) {
        final String eventThreadId = params.path("threadId").asText(this.threadId);
        final String turnId = params.path("turnId").asText(null);
        this.turnEventCollector.touch(eventThreadId, turnId);
        final JsonNode changes = params.path("changes");
        this.emit(CodexProgressEvent.builder()
                .executionId(this.progressContext.executionId())
                .ticketId(this.progressContext.ticketId())
                .laneId(this.progressContext.laneId())
                .agentId(this.progressContext.agentId())
                .scope(this.progressContext.scope())
                .sessionId(this.progressContext.sessionId())
                .threadId(eventThreadId)
                .turnId(turnId)
                .processPid(this.process.pid())
                .eventType(CodexProgressEventType.FILE_CHANGE)
                .fileCount(changes.isArray() ? changes.size() : null)
                .occurredAt(Instant.now())
                .build());
    }

    private void handleTurnCompleted(final JsonNode params) {
        final String eventThreadId = params.path("threadId").asText(this.threadId);
        final JsonNode turnNode = params.path("turn");
        final String turnId = turnNode.path("id").asText(null);
        this.emit(CodexProgressEvent.builder()
                .executionId(this.progressContext.executionId())
                .ticketId(this.progressContext.ticketId())
                .laneId(this.progressContext.laneId())
                .agentId(this.progressContext.agentId())
                .scope(this.progressContext.scope())
                .sessionId(this.progressContext.sessionId())
                .threadId(eventThreadId)
                .turnId(turnId)
                .processPid(this.process.pid())
                .eventType(CodexProgressEventType.TURN_COMPLETED)
                .status(turnNode.path("status").asText(null))
                .durationMs(turnNode.hasNonNull("durationMs") ? turnNode.get("durationMs").asLong() : null)
                .occurredAt(Instant.now())
                .build());
        this.turnEventCollector.registerCompletedTurn(eventThreadId, turnId, turnNode);
    }

    private void failPendingRequests(final String reason) {
        final List<Map.Entry<String, CompletableFuture<JsonNode>>> entries = new ArrayList<>(this.pendingRequests.entrySet());
        this.pendingRequests.clear();
        for (final Map.Entry<String, CompletableFuture<JsonNode>> entry : entries) {
            final RequestContext context = this.requestContexts.remove(entry.getKey());
            entry.getValue().completeExceptionally(CodexAppServerRequestException.protocolError(
                    context == null ? "unknown" : context.method(),
                    entry.getKey(),
                    context == null ? List.of() : context.paramKeys(),
                    reason,
                    this.stderrTail(),
                    this.processExitStatus(),
                    this.initializeSucceeded,
                    this.initializedSent,
                    this.codexVersion,
                    this.command,
                    null
            ));
        }
    }

    private List<String> paramKeys(final JsonNode params) {
        if (params == null || !params.isObject()) {
            return List.of();
        }
        final List<String> keys = new ArrayList<>();
        params.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private String stderrTail() {
        synchronized (this.stderrTail) {
            return String.join(" | ", this.stderrTail);
        }
    }

    private Integer processExitStatus() {
        if (this.process.isAlive()) {
            return null;
        }
        try {
            return this.process.exitValue();
        } catch (final IllegalThreadStateException ex) {
            return null;
        }
    }

    private String truncate(final String value, final int maxChars) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private void emit(final CodexProgressEvent event) {
        if (!this.progressProperties.isEnabled() || this.progressObserver == null || event == null) {
            return;
        }
        this.progressObserver.onEvent(event);
    }

    private CodexAppServerRequestException protocolError(final String method,
                                                         final String requestId,
                                                         final List<String> paramKeys,
                                                         final String reason,
                                                         final Throwable cause) {
        return CodexAppServerRequestException.protocolError(
                method,
                requestId,
                paramKeys,
                reason,
                this.stderrTail(),
                this.processExitStatus(),
                this.initializeSucceeded,
                this.initializedSent,
                this.codexVersion,
                this.command,
                cause
        );
    }

    @Override
    public void close() {
        this.process.destroy();
        try {
            if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
                this.process.destroyForcibly();
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            this.process.destroyForcibly();
        }
        this.turnEventCollector.clearThread(this.threadId);
        this.stdoutReaderThread.interrupt();
        this.stderrReaderThread.interrupt();
        this.emit(CodexProgressEvent.builder()
                .executionId(this.progressContext.executionId())
                .ticketId(this.progressContext.ticketId())
                .laneId(this.progressContext.laneId())
                .agentId(this.progressContext.agentId())
                .scope(this.progressContext.scope())
                .sessionId(this.progressContext.sessionId())
                .threadId(this.threadId)
                .processPid(this.process.pid())
                .eventType(CodexProgressEventType.PROCESS_TERMINATED)
                .status(this.process.isAlive() ? "alive" : "terminated")
                .occurredAt(Instant.now())
                .build());
    }

    record ProgressContext(
            UUID executionId,
            UUID ticketId,
            UUID laneId,
            String agentId,
            String scope,
            String sessionId
    ) {
    }

    private record RequestContext(String method, List<String> paramKeys) {
    }
}
