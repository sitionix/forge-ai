package com.sitionix.forgeai.infrastructure.codexcli.adapter.appserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class CodexJsonRpcClient implements AutoCloseable {

    private final ObjectMapper objectMapper;
    private final Process process;
    private final Writer writer;
    private final AtomicLong requestIdSequence = new AtomicLong(1L);
    private final Map<String, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private final CodexTurnEventCollector turnEventCollector = new CodexTurnEventCollector();
    private final Thread readerThread;

    CodexJsonRpcClient(final ObjectMapper objectMapper, final Process process) {
        this.objectMapper = objectMapper;
        this.process = process;
        this.writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        this.readerThread = Thread.ofVirtual().name("codex-appserver-reader-" + process.pid()).start(this::readLoop);
    }

    JsonNode request(final String method, final JsonNode params, final Duration timeout) {
        final String requestId = Long.toString(this.requestIdSequence.getAndIncrement());
        final CompletableFuture<JsonNode> future = new CompletableFuture<>();
        this.pendingRequests.put(requestId, future);
        try {
            final ObjectNode request = this.objectMapper.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", requestId);
            request.put("method", method);
            request.set("params", params == null ? this.objectMapper.createObjectNode() : params);
            synchronized (this.writer) {
                this.writer.write(this.objectMapper.writeValueAsString(request));
                this.writer.write('\n');
                this.writer.flush();
            }
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final Exception e) {
            this.pendingRequests.remove(requestId);
            throw new IllegalStateException("Failed Codex app-server request method=" + method, e);
        }
    }

    JsonNode awaitCompletedTurn(final String turnId, final Duration timeout) {
        return this.turnEventCollector.awaitCompletedTurn(turnId, timeout);
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                final JsonNode message = this.objectMapper.readTree(line);
                if (message.hasNonNull("id")) {
                    this.handleResponse(message);
                    continue;
                }
                if (message.hasNonNull("method")) {
                    this.handleNotification(message);
                }
            }
        } catch (final IOException e) {
            this.pendingRequests.values().forEach(future -> future.completeExceptionally(e));
        }
    }

    private void handleResponse(final JsonNode message) {
        final String requestId = message.path("id").asText();
        final CompletableFuture<JsonNode> future = this.pendingRequests.remove(requestId);
        if (future == null) {
            return;
        }
        if (message.hasNonNull("error")) {
            future.completeExceptionally(new IllegalStateException(message.get("error").toString()));
            return;
        }
        future.complete(message.path("result"));
    }

    private void handleNotification(final JsonNode message) {
        final String method = message.path("method").asText();
        if (!"turn/completed".equals(method)) {
            return;
        }
        final JsonNode params = message.path("params");
        final JsonNode turn = params.path("turn");
        final String turnId = turn.path("id").asText(null);
        this.turnEventCollector.registerCompletedTurn(turnId, turn);
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
        this.readerThread.interrupt();
    }
}
